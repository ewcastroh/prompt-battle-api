package io.github.ewcastroh.promptbattle.generation;

import io.github.ewcastroh.promptbattle.game.FailureKind;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * fal.ai through its queue API (SPEC §5.2, docs re-read 2026-11-20), never the synchronous {@code fal.run}:
 * <ol>
 * <li>{@code POST {queue}/{model}} → {@code request_id}, {@code status_url}, {@code response_url},
 * {@code cancel_url}. Those returned URLs are used as is (they carry only the app id).</li>
 * <li>Poll {@code status_url}: 202 {@code IN_QUEUE}/{@code IN_PROGRESS}, 200 {@code COMPLETED}. A failed or
 * cancelled request is also COMPLETED; the result endpoint tells which.</li>
 * <li>{@code GET response_url}: 200 with {@code images[0].url} and {@code has_nsfw_concepts}; a flagged image
 * is black → SAFETY_BLOCKED. 422 {@code content_policy_violation} → SAFETY_BLOCKED.</li>
 * <li>Download the image from fal's CDN at once, without the key.</li>
 * </ol>
 * The whole call, polling included, has {@code timeout}. {@code X-Fal-Request-Start-Timeout} makes fal drop a
 * request that could not start in time; when we give up before COMPLETED the request is cancelled (best effort,
 * off the job's thread).
 * {@code x-app-fal-disable-fallback} keeps fal from answering with another model: every player of a round must
 * get the same model. The queue API never answers 429 for concurrency (requests wait in fal's queue), so only
 * our own slots bound concurrency.
 */
public final class FalImageGenerator implements ImageGenerator {

    public static final String ID = "fal";
    private static final Logger log = LoggerFactory.getLogger(FalImageGenerator.class);
    static final Duration START_TIMEOUT_MARGIN = Duration.ofSeconds(5);
    static final Duration CANCEL_BUDGET = Duration.ofSeconds(2);
    static final int MAX_POLL_FAILURES = 3;

    private final String key;
    private final String model;
    private final int maxConcurrency;
    private final URI submitUri;
    private final Duration timeout;
    private final Duration pollInterval;
    private final ProviderHttp http;
    private final RetryPolicy retry;
    /** Runs best-effort cancels off the job's thread, so a failed call returns (and frees its slot) at once. */
    private final Executor cancels;

    /** A failure decided by the adapter; carries the kind the dispatcher and the game see. */
    private static final class Fail extends Exception {
        final FailureKind kind;

        Fail(FailureKind kind, String message) {
            super(message, null, false, false);
            this.kind = kind;
        }
    }

    private record Submitted(String requestId, URI status, URI response, URI cancel) {
    }

    public FalImageGenerator(String key, String model, int maxConcurrency, String queueUrl, Duration timeout,
            Duration pollInterval, ProviderHttp http, RetryPolicy retry) {
        this(key, model, maxConcurrency, queueUrl, timeout, pollInterval, http, retry,
                task -> Thread.ofVirtual().name("fal-cancel").start(task));
    }

    /** {@code cancels} runs the cancel calls (tests pass a direct executor to observe them deterministically). */
    FalImageGenerator(String key, String model, int maxConcurrency, String queueUrl, Duration timeout,
            Duration pollInterval, ProviderHttp http, RetryPolicy retry, Executor cancels) {
        this.cancels = cancels;
        this.key = key;
        this.model = model;
        this.maxConcurrency = maxConcurrency;
        String base = queueUrl.endsWith("/") ? queueUrl.substring(0, queueUrl.length() - 1) : queueUrl;
        this.submitUri = URI.create(base + "/" + model);
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
        Submitted submitted = null;
        boolean completed = false;
        try {
            submitted = submit(request, deadline);
            awaitCompleted(submitted, deadline);
            completed = true;
            byte[] image = result(submitted, deadline);
            String type = ProviderHttp.imageType(image);
            return new GenerationResult.Success(image, type, model, (System.nanoTime() - started) / 1_000_000);
        } catch (Fail f) {
            if (submitted != null && !completed) {
                // We gave up on a request fal may still run (and bill): free its queue slot, best effort.
                Submitted s = submitted;
                cancels.execute(() -> cancel(s));
            }
            log.debug("fal generation failed: {} {}", f.kind, f.getMessage());
            return new GenerationResult.Failure(f.kind, f.getMessage());
        }
    }

    private Map<String, String> auth() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Key " + key);
        headers.put("Accept", "application/json");
        return headers;
    }

    private Submitted submit(GenerationRequest request, Deadline deadline) throws Fail {
        ObjectNode body = ProviderJson.object();
        body.put("prompt", request.prompt());
        body.put("seed", request.seed());
        ObjectNode size = body.putObject("image_size");
        size.put("width", request.width());
        size.put("height", request.height());
        body.put("num_inference_steps", 4);
        body.put("num_images", 1);
        body.put("output_format", "jpeg");
        body.put("enable_safety_checker", true);
        byte[] json = ProviderJson.bytes(body);
        Map<String, String> headers = auth();
        long startSeconds = Math.max(1, deadline.remaining().minus(START_TIMEOUT_MARGIN).toSeconds());
        headers.put("X-Fal-Request-Start-Timeout", Long.toString(startSeconds));
        headers.put("x-app-fal-disable-fallback", "true");

        ProviderHttp.Response r = call(() -> http.postJson(submitUri, headers, json, deadline), deadline);
        if (!r.ok()) {
            throw failure(r, "submit");
        }
        JsonNode node = ProviderJson.parse(r.body());
        String requestId = ProviderJson.text(node, "request_id");
        String status = ProviderJson.text(node, "status_url");
        String response = ProviderJson.text(node, "response_url");
        String cancel = ProviderJson.text(node, "cancel_url");
        if (requestId == null || !ProviderHttp.isHttpUrl(status) || !ProviderHttp.isHttpUrl(response)) {
            throw new Fail(FailureKind.INVALID_RESPONSE, "submit response without request URLs");
        }
        return new Submitted(requestId, URI.create(status), URI.create(response),
                ProviderHttp.isHttpUrl(cancel) ? URI.create(cancel) : null);
    }

    private void awaitCompleted(Submitted s, Deadline deadline) throws Fail {
        int failures = 0;
        while (true) {
            ProviderHttp.Response r = call(() -> http.get(s.status(), auth(), deadline), deadline);
            if (r.status() == 200 || r.status() == 202) {
                JsonNode node = ProviderJson.parse(r.body());
                String status = ProviderJson.text(node, "status");
                if ("COMPLETED".equals(status)) {
                    return;
                }
                if (!"IN_QUEUE".equals(status) && !"IN_PROGRESS".equals(status)) {
                    throw new Fail(FailureKind.INVALID_RESPONSE, "unknown queue status");
                }
                failures = 0;
            } else if (r.status() == 404 || r.status() == 401 || r.status() == 403) {
                throw new Fail(FailureKind.PROVIDER_ERROR, "status HTTP " + r.status());
            } else if (++failures >= MAX_POLL_FAILURES) {
                throw new Fail(FailureKind.PROVIDER_ERROR, "status HTTP " + r.status());
            }
            if (deadline.expired()) {
                throw new Fail(FailureKind.TIMEOUT, "not completed in time");
            }
            if (!deadline.sleep(pollInterval)) {
                throw new Fail(FailureKind.PROVIDER_ERROR, "interrupted");
            }
            if (deadline.expired()) {
                throw new Fail(FailureKind.TIMEOUT, "not completed in time");
            }
        }
    }

    private byte[] result(Submitted s, Deadline deadline) throws Fail {
        ProviderHttp.Response r = call(() -> http.get(s.response(), auth(), deadline), deadline);
        if (!r.ok()) {
            throw failure(r, "result");
        }
        JsonNode node = ProviderJson.parse(r.body());
        if (node == null) {
            throw new Fail(FailureKind.INVALID_RESPONSE, "result is not JSON");
        }
        JsonNode nsfw = node.get("has_nsfw_concepts");
        if (nsfw != null && nsfw.isArray()) {
            for (JsonNode flag : nsfw.values()) {
                if (flag.isBoolean() && flag.asBoolean()) {
                    throw new Fail(FailureKind.SAFETY_BLOCKED, "flagged by the safety checker (black image)");
                }
            }
        }
        JsonNode images = node.get("images");
        String url = images != null && images.isArray() && !images.isEmpty() ? ProviderJson.text(images.get(0), "url")
                : null;
        if (!ProviderHttp.isHttpUrl(url)) {
            throw new Fail(FailureKind.INVALID_RESPONSE, "result without an image URL");
        }
        // fal's CDN is public: never send the key there.
        ProviderHttp.Response image = call(() -> http.get(URI.create(url), Map.of(), deadline), deadline);
        if (!image.ok()) {
            throw new Fail(FailureKind.PROVIDER_ERROR, "image download HTTP " + image.status());
        }
        if (image.truncated() || ProviderHttp.imageType(image.body()) == null) {
            throw new Fail(FailureKind.INVALID_RESPONSE, "downloaded file is not a JPEG or PNG");
        }
        return image.body();
    }

    /** Maps an error response of submit or result (API reference error table). */
    private static Fail failure(ProviderHttp.Response r, String step) {
        int status = r.status();
        if (status == 422 && hasErrorType(r, "content_policy_violation")) {
            return new Fail(FailureKind.SAFETY_BLOCKED, "content policy violation");
        }
        if (status == 504) {
            return new Fail(FailureKind.TIMEOUT, step + " HTTP 504");
        }
        if (status == 429) {
            return new Fail(FailureKind.RATE_LIMITED, step + " HTTP 429");
        }
        if (status >= 200 && status < 300) {
            return new Fail(FailureKind.INVALID_RESPONSE, step + " unexpected HTTP " + status);
        }
        return new Fail(FailureKind.PROVIDER_ERROR, step + " HTTP " + status);
    }

    /** {@code {"detail":[{"type":...}]}} (model errors) or {@code {"error_type":...}} (request errors). */
    static boolean hasErrorType(ProviderHttp.Response r, String type) {
        JsonNode node = ProviderJson.parse(r.body());
        if (node == null) {
            return false;
        }
        if (type.equals(ProviderJson.text(node, "error_type")) || type.equals(ProviderJson.text(node, "type"))) {
            return true;
        }
        JsonNode detail = node.get("detail");
        if (detail != null && detail.isArray()) {
            for (JsonNode d : detail.values()) {
                if (type.equals(ProviderJson.text(d, "type"))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** fal says "a retry cannot succeed" with {@code X-Fal-Needs-Retry: 0}; it documents no Retry-After. */
    private static final ProviderHttp.RetryRule RETRY = new ProviderHttp.RetryRule() {
        @Override
        public boolean retryable(ProviderHttp.Response r) {
            return (r.status() == 429 || r.status() >= 500) && !"0".equals(r.header("X-Fal-Needs-Retry"));
        }

        @Override
        public Duration hint(ProviderHttp.Response r) {
            return ProviderHttp.retryAfter(r);
        }
    };

    /** One HTTP call with one retry for 429/5xx and connection errors. */
    private ProviderHttp.Response call(ProviderHttp.HttpCall c, Deadline deadline) throws Fail {
        try {
            return ProviderHttp.withRetry(c, retry, RETRY, deadline);
        } catch (ProviderHttp.CallFailure e) {
            throw new Fail(e.timeout() ? FailureKind.TIMEOUT : FailureKind.PROVIDER_ERROR,
                    e.timeout() ? "HTTP call timed out" : "connection failed: " + e.getMessage());
        }
    }

    /** Best effort: frees fal's queue when we gave up (a request already running may still finish). */
    private void cancel(Submitted s) {
        if (s.cancel() == null) {
            return;
        }
        try {
            http.put(s.cancel(), auth(), Deadline.after(CANCEL_BUDGET));
        } catch (ProviderHttp.CallFailure | RuntimeException e) {
            log.debug("fal cancel failed: {}", e.getMessage());
        }
    }
}
