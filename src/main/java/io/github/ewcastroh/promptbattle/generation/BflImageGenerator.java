package io.github.ewcastroh.promptbattle.generation;

import io.github.ewcastroh.promptbattle.game.FailureKind;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Black Forest Labs direct API, FLUX.2 [klein] 9B (SPEC §5.2, docs re-read 2026-11-20):
 * <ol>
 * <li>{@code POST {base}/v1/{model}} with {@code x-key} → {@code id}, {@code polling_url}.</li>
 * <li>Poll {@code polling_url} exactly as returned (it points at the region holding the task):
 * {@code Pending}/{@code Reasoning}/{@code Generating} → wait; {@code Ready} → {@code result.sample};
 * {@code Request Moderated}/{@code Content Moderated} → SAFETY_BLOCKED; {@code Error}, {@code Failed},
 * {@code Task not found}, {@code No Card Generated} → PROVIDER_ERROR. A 503 may still carry a JSON status.</li>
 * <li>Download the signed sample at once (it expires after 10 minutes), without the key.</li>
 * </ol>
 * 429 (over the account's 24 active tasks) gets one retry with backoff; 402 (no credits) never does. BFL has no
 * cancel endpoint: a call that runs out of time is simply abandoned.
 */
public final class BflImageGenerator implements ImageGenerator {

    public static final String ID = "bfl";
    private static final Logger log = LoggerFactory.getLogger(BflImageGenerator.class);
    static final int MAX_POLL_FAILURES = 3;
    private static final Set<String> MODERATED = Set.of("Request Moderated", "Content Moderated");
    private static final Set<String> FAILED = Set.of("Error", "Failed", "Task not found", "No Card Generated");

    private final String key;
    private final String model;
    private final int maxConcurrency;
    private final URI submitUri;
    private final int safetyTolerance;
    private final Duration timeout;
    private final Duration pollInterval;
    private final ProviderHttp http;
    private final RetryPolicy retry;

    private static final class Fail extends Exception {
        final FailureKind kind;

        Fail(FailureKind kind, String message) {
            super(message, null, false, false);
            this.kind = kind;
        }
    }

    public BflImageGenerator(String key, String model, int maxConcurrency, String baseUrl, int safetyTolerance,
            Duration timeout, Duration pollInterval, ProviderHttp http, RetryPolicy retry) {
        this.key = key;
        this.model = model;
        this.maxConcurrency = maxConcurrency;
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.submitUri = URI.create(base + "/v1/" + model);
        this.safetyTolerance = safetyTolerance;
        this.timeout = timeout;
        this.pollInterval = pollInterval;
        this.http = http;
        this.retry = retry;
    }

    @Override
    public String providerId() {
        return ID;
    }

    @Override
    public String modelId() {
        return model;
    }

    @Override
    public int maxConcurrency() {
        return maxConcurrency;
    }

    @Override
    public GenerationResult generate(GenerationRequest request) {
        long started = System.nanoTime();
        Deadline deadline = Deadline.after(timeout);
        try {
            URI polling = submit(request, deadline);
            String sample = awaitReady(polling, deadline);
            byte[] image = download(sample, deadline);
            return new GenerationResult.Success(image, ProviderHttp.imageType(image), model,
                    (System.nanoTime() - started) / 1_000_000);
        } catch (Fail f) {
            log.debug("BFL generation failed: {} {}", f.kind, f.getMessage());
            return new GenerationResult.Failure(f.kind, f.getMessage());
        }
    }

    private Map<String, String> auth() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-key", key);
        headers.put("Accept", "application/json");
        return headers;
    }

    private URI submit(GenerationRequest request, Deadline deadline) throws Fail {
        ObjectNode body = ProviderJson.object();
        body.put("prompt", request.prompt());
        body.put("seed", request.seed());
        body.put("width", request.width());
        body.put("height", request.height());
        body.put("output_format", "jpeg");
        body.put("safety_tolerance", safetyTolerance);
        byte[] json = ProviderJson.bytes(body);
        ProviderHttp.Response r = call(() -> http.postJson(submitUri, auth(), json, deadline), deadline);
        if (!r.ok()) {
            throw new Fail(r.status() == 429 ? FailureKind.RATE_LIMITED : FailureKind.PROVIDER_ERROR,
                    "submit HTTP " + r.status());
        }
        String polling = ProviderJson.text(ProviderJson.parse(r.body()), "polling_url");
        if (!ProviderHttp.isHttpUrl(polling)) {
            throw new Fail(FailureKind.INVALID_RESPONSE, "submit response without polling_url");
        }
        return URI.create(polling);
    }

    private String awaitReady(URI polling, Deadline deadline) throws Fail {
        int failures = 0;
        while (true) {
            ProviderHttp.Response r = call(() -> http.get(polling, auth(), deadline), deadline);
            JsonNode node = ProviderJson.parse(r.body());
            String status = ProviderJson.text(node, "status");
            if (r.ok() && status == null) {
                throw new Fail(FailureKind.INVALID_RESPONSE, "poll response without status");
            }
            if (status != null && (r.ok() || r.status() == 503)) {
                if ("Ready".equals(status)) {
                    JsonNode result = node.get("result");
                    String sample = ProviderJson.text(result, "sample");
                    if (!ProviderHttp.isHttpUrl(sample)) {
                        throw new Fail(FailureKind.INVALID_RESPONSE, "Ready without result.sample");
                    }
                    return sample;
                }
                if (MODERATED.contains(status)) {
                    throw new Fail(FailureKind.SAFETY_BLOCKED, status);
                }
                if (FAILED.contains(status)) {
                    throw new Fail(FailureKind.PROVIDER_ERROR, status);
                }
                failures = 0; // Pending, Reasoning, Generating or undocumented: poll until the deadline
            } else if (r.status() == 404 || r.status() == 402 || r.status() == 403 || r.status() == 401) {
                throw new Fail(FailureKind.PROVIDER_ERROR, "poll HTTP " + r.status());
            } else if (++failures >= MAX_POLL_FAILURES) {
                throw new Fail(FailureKind.PROVIDER_ERROR, "poll HTTP " + r.status());
            }
            if (deadline.expired()) {
                throw new Fail(FailureKind.TIMEOUT, "not ready in time");
            }
            if (!deadline.sleep(pollInterval)) {
                throw new Fail(FailureKind.PROVIDER_ERROR, "interrupted");
            }
            if (deadline.expired()) {
                throw new Fail(FailureKind.TIMEOUT, "not ready in time");
            }
        }
    }

    private byte[] download(String sample, Deadline deadline) throws Fail {
        // A signed delivery URL: never send the key there.
        ProviderHttp.Response r = call(() -> http.get(URI.create(sample), Map.of(), deadline), deadline);
        if (!r.ok()) {
            throw new Fail(FailureKind.PROVIDER_ERROR, "sample download HTTP " + r.status());
        }
        if (r.truncated() || ProviderHttp.imageType(r.body()) == null) {
            throw new Fail(FailureKind.INVALID_RESPONSE, "sample is not a JPEG or PNG");
        }
        return r.body();
    }

    private ProviderHttp.Response call(ProviderHttp.HttpCall c, Deadline deadline) throws Fail {
        try {
            return ProviderHttp.withRetry(c, retry, ProviderHttp.TRANSIENT, deadline);
        } catch (ProviderHttp.CallFailure e) {
            throw new Fail(e.timeout() ? FailureKind.TIMEOUT : FailureKind.PROVIDER_ERROR,
                    e.timeout() ? "HTTP call timed out" : "connection failed: " + e.getMessage());
        }
    }
}
