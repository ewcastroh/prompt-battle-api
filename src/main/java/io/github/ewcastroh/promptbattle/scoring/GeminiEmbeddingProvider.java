package io.github.ewcastroh.promptbattle.scoring;

import io.github.ewcastroh.promptbattle.generation.Deadline;
import io.github.ewcastroh.promptbattle.generation.ProviderHttp;
import io.github.ewcastroh.promptbattle.generation.ProviderJson;
import io.github.ewcastroh.promptbattle.generation.RetryPolicy;
import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Gemini API image embeddings, {@code models.embedContent} (SPEC §4, D-013; docs re-read 2026-11-20):
 * <ul>
 * <li>{@code POST {base}/v1beta/models/{model}:embedContent} with {@code x-goog-api-key}; ONE image per call
 * (several parts would merge into one vector).</li>
 * <li>The size goes in {@code embedContentConfig.outputDimensionality} (the top-level field is deprecated); the
 * response must have exactly that many values in {@code embedding.values}.</li>
 * <li>Every request first passes the free-tier {@link EmbeddingRateLimiter}; {@code usageMetadata.promptTokenCount}
 * corrects the token reservation.</li>
 * <li>429 RESOURCE_EXHAUSTED with a {@code QuotaFailure} whose quota id is per day (or the string code
 * {@code quota_exceeded}) → QUOTA_EXHAUSTED, no retry, nothing more is sent today. Any other 429, 408 and 5xx
 * get one retry (honoring {@code RetryInfo.retryDelay}); 400/402/403/404 never do.</li>
 * </ul>
 */
public final class GeminiEmbeddingProvider implements EmbeddingProvider {

    public static final String ID = "gemini";
    /** Pause after a per-minute 429 without a RetryInfo hint. */
    static final Duration DEFAULT_PAUSE = Duration.ofSeconds(1);

    private final String key;
    private final String model;
    private final int dimensions;
    private final URI uri;
    private final Duration timeout;
    private final ProviderHttp http;
    private final RetryPolicy retry;
    private final EmbeddingRateLimiter limiter;

    public GeminiEmbeddingProvider(String key, String model, int dimensions, String baseUrl, Duration timeout,
            ProviderHttp http, RetryPolicy retry, EmbeddingRateLimiter limiter) {
        this.key = key;
        this.model = model;
        this.dimensions = dimensions;
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.uri = URI.create(base + "/v1beta/models/" + model + ":embedContent");
        this.timeout = timeout;
        this.http = http;
        this.retry = retry;
        this.limiter = limiter;
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
    public EmbeddingRateLimiter rateLimiter() {
        return limiter;
    }

    @Override
    public double[] embed(byte[] image, String mimeType) {
        return embed(image, mimeType, true);
    }

    /** Prefetch: one request only if the limiter has a permit right now, no retry. */
    @Override
    public double[] embedWithoutWaiting(byte[] image, String mimeType) {
        return embed(image, mimeType, false);
    }

    private double[] embed(byte[] image, String mimeType, boolean wait) {
        ObjectNode body = ProviderJson.object();
        body.put("model", "models/" + model);
        ObjectNode inline = body.putObject("content").putArray("parts").addObject().putObject("inlineData");
        inline.put("mimeType", mimeType);
        inline.put("data", Base64.getEncoder().encodeToString(image));
        body.putObject("embedContentConfig").put("outputDimensionality", dimensions);
        byte[] json = ProviderJson.bytes(body);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-goog-api-key", key);
        headers.put("Accept", "application/json");

        Deadline deadline = null;
        EmbeddingException last = null;
        for (int retryNo = 1; ; retryNo++) {
            EmbeddingRateLimiter.Permit permit = permit(wait, deadline, last);
            if (deadline == null) {
                deadline = Deadline.after(timeout); // the budget starts once the first request may go
            }
            Deadline callDeadline = deadline;
            ProviderHttp.Response r;
            try {
                r = http.postJson(uri, headers, json, callDeadline);
            } catch (ProviderHttp.CallFailure e) {
                if (e.timeout()) {
                    throw new EmbeddingException(EmbeddingException.Kind.TIMEOUT, "Gemini call timed out");
                }
                last = new EmbeddingException(EmbeddingException.Kind.PROVIDER_ERROR, "Gemini connection failed");
                if (wait && sleepBeforeRetry(retryNo, null, callDeadline)) {
                    continue;
                }
                throw last;
            }
            JsonNode node = ProviderJson.parse(r.body());
            if (r.ok()) {
                limiter.recordTokens(permit, tokens(node));
                return vector(node);
            }
            if (r.status() == 429 && dailyQuota(node)) {
                limiter.exhaustToday();
                throw new EmbeddingException(EmbeddingException.Kind.QUOTA_EXHAUSTED, "Gemini daily quota exhausted");
            }
            Duration hint = retryDelay(node);
            if (r.status() == 429) {
                // Google's minute window is full (its limits are "not guaranteed"): hold every request, so the
                // scorer's next try waits it out instead of firing into the same window.
                limiter.pauseFor(hint != null ? hint : DEFAULT_PAUSE);
            }
            last = new EmbeddingException(kindOf(r.status()), "Gemini HTTP " + r.status());
            boolean retryable = r.status() == 429 || r.status() == 408 || r.status() >= 500;
            if (wait && retryable && sleepBeforeRetry(retryNo, hint, callDeadline)) {
                continue;
            }
            throw last;
        }
    }

    /**
     * The first request may wait for the limiter (up to its max wait); a retry only within the call's budget,
     * and when it can't, the retried failure is reported (no permit is taken, nothing is counted).
     */
    private EmbeddingRateLimiter.Permit permit(boolean wait, Deadline deadline, EmbeddingException last) {
        if (!wait) {
            EmbeddingRateLimiter.Permit permit = limiter.tryAcquire();
            if (permit == null) {
                throw new EmbeddingException.Skipped("Gemini rate limit: no capacity right now");
            }
            return permit;
        }
        if (deadline == null) {
            return limiter.acquire(); // throws when the daily quota is gone
        }
        Duration left = deadline.remaining().minus(RetryPolicy.MIN_CALL);
        try {
            return limiter.acquire(left.isNegative() ? Duration.ZERO : left);
        } catch (EmbeddingException e) {
            if (e.kind() == EmbeddingException.Kind.QUOTA_EXHAUSTED || last == null) {
                throw e;
            }
            throw last;
        }
    }

    private boolean sleepBeforeRetry(int retryNo, Duration hint, Deadline deadline) {
        Duration delay = retry.delay(retryNo, hint, deadline);
        return delay != null && deadline.sleep(delay);
    }

    private static EmbeddingException.Kind kindOf(int status) {
        if (status == 429) {
            return EmbeddingException.Kind.RATE_LIMITED;
        }
        if (status == 408 || status == 504 || status == 499) {
            return EmbeddingException.Kind.TIMEOUT;
        }
        return EmbeddingException.Kind.PROVIDER_ERROR;
    }

    private double[] vector(JsonNode node) {
        JsonNode values = node == null ? null : node.path("embedding").get("values");
        if (values == null || !values.isArray() || values.size() != dimensions) {
            throw new EmbeddingException(EmbeddingException.Kind.INVALID_RESPONSE,
                    "Gemini response without " + dimensions + " embedding values");
        }
        double[] v = new double[values.size()];
        for (int i = 0; i < v.length; i++) {
            JsonNode x = values.get(i);
            if (!x.isNumber() || !Double.isFinite(x.doubleValue())) {
                throw new EmbeddingException(EmbeddingException.Kind.INVALID_RESPONSE, "Non-numeric embedding value");
            }
            v[i] = x.doubleValue();
        }
        return v;
    }

    private static int tokens(JsonNode node) {
        JsonNode count = node == null ? null : node.path("usageMetadata").get("promptTokenCount");
        return count != null && count.isNumber() ? count.asInt() : 0;
    }

    /** A daily quota: {@code QuotaFailure} with a per-day quota id, or the string code {@code quota_exceeded}. */
    static boolean dailyQuota(JsonNode node) {
        JsonNode error = node == null ? null : node.get("error");
        if (error == null) {
            return false;
        }
        JsonNode code = error.get("code");
        if (code != null && code.isString() && "quota_exceeded".equals(code.stringValue())) {
            return true;
        }
        JsonNode details = error.get("details");
        if (details == null || !details.isArray()) {
            return false;
        }
        for (JsonNode d : details.values()) {
            String type = ProviderJson.text(d, "@type");
            JsonNode violations = d.get("violations");
            if (type == null || !type.endsWith("google.rpc.QuotaFailure") || violations == null || !violations.isArray()) {
                continue;
            }
            for (JsonNode v : violations.values()) {
                String id = ProviderJson.text(v, "quotaId");
                String metric = ProviderJson.text(v, "quotaMetric");
                if (perDay(id) || perDay(metric)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean perDay(String value) {
        return value != null && value.toLowerCase(Locale.ROOT).contains("perday");
    }

    /** {@code RetryInfo.retryDelay}, a protobuf duration such as {@code "1s"} or {@code "0.5s"}. */
    static Duration retryDelay(JsonNode node) {
        JsonNode details = node == null ? null : node.path("error").get("details");
        if (details == null || !details.isArray()) {
            return null;
        }
        for (JsonNode d : details.values()) {
            String type = ProviderJson.text(d, "@type");
            String delay = ProviderJson.text(d, "retryDelay");
            if (type != null && type.endsWith("google.rpc.RetryInfo") && delay != null && delay.endsWith("s")) {
                try {
                    double seconds = Double.parseDouble(delay.substring(0, delay.length() - 1));
                    return seconds < 0 ? null : Duration.ofMillis((long) (seconds * 1000));
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return null;
    }
}
