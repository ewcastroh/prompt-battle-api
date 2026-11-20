package io.github.ewcastroh.promptbattle.scoring;

import io.github.ewcastroh.promptbattle.generation.Deadline;
import io.github.ewcastroh.promptbattle.generation.ProviderHttp;
import io.github.ewcastroh.promptbattle.generation.ProviderJson;
import io.github.ewcastroh.promptbattle.generation.RetryPolicy;
import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Cohere Embed v2 image embeddings (optional backup, D-011; docs re-read 2026-11-20):
 * {@code POST {base}/v2/embed} with a Bearer key, one image per call as an {@code inputs[].content[]}
 * {@code image_url} data URI with {@code input_type: search_document} (valid for every documented rule),
 * {@code embedding_types: ["float"]}, {@code output_dimension} from configuration; the vector is
 * {@code embeddings.float[0]}. 429, 500, 503 and 504 get one retry; 402 (billing limit) is a used-up quota.
 */
public final class CohereEmbeddingProvider implements EmbeddingProvider {

    public static final String ID = "cohere";

    private final String key;
    private final String model;
    private final int dimensions;
    private final URI uri;
    private final Duration timeout;
    private final ProviderHttp http;
    private final RetryPolicy retry;

    public CohereEmbeddingProvider(String key, String model, int dimensions, String baseUrl, Duration timeout,
            ProviderHttp http, RetryPolicy retry) {
        this.key = key;
        this.model = model;
        this.dimensions = dimensions;
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.uri = URI.create(base + "/v2/embed");
        this.timeout = timeout;
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
    public double[] embed(byte[] image, String mimeType) {
        ObjectNode body = ProviderJson.object();
        body.put("model", model);
        body.put("input_type", "search_document");
        ObjectNode part = body.putArray("inputs").addObject().putArray("content").addObject();
        part.put("type", "image_url");
        part.putObject("image_url").put("url",
                "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(image));
        body.putArray("embedding_types").add("float");
        body.put("output_dimension", dimensions);
        byte[] json = ProviderJson.bytes(body);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + key);
        headers.put("Accept", "application/json");

        Deadline deadline = Deadline.after(timeout);
        ProviderHttp.Response r;
        try {
            r = ProviderHttp.withRetry(() -> http.postJson(uri, headers, json, deadline), retry, RETRY, deadline);
        } catch (ProviderHttp.CallFailure e) {
            throw new EmbeddingException(e.timeout() ? EmbeddingException.Kind.TIMEOUT
                    : EmbeddingException.Kind.PROVIDER_ERROR, "Cohere call failed");
        }
        if (r.ok()) {
            return vector(ProviderJson.parse(r.body()));
        }
        EmbeddingException.Kind kind = switch (r.status()) {
            case 429 -> EmbeddingException.Kind.RATE_LIMITED;
            case 402 -> EmbeddingException.Kind.QUOTA_EXHAUSTED;
            case 499, 504 -> EmbeddingException.Kind.TIMEOUT;
            default -> EmbeddingException.Kind.PROVIDER_ERROR;
        };
        throw new EmbeddingException(kind, "Cohere HTTP " + r.status());
    }

    /** 429, 500, 503 and 504 are worth a retry (Cohere documents no Retry-After; it is honored if present). */
    private static final ProviderHttp.RetryRule RETRY = new ProviderHttp.RetryRule() {
        @Override
        public boolean retryable(ProviderHttp.Response r) {
            return r.status() == 429 || r.status() == 500 || r.status() == 503 || r.status() == 504;
        }

        @Override
        public Duration hint(ProviderHttp.Response r) {
            return ProviderHttp.retryAfter(r);
        }
    };

    private double[] vector(JsonNode node) {
        JsonNode rows = node == null ? null : node.path("embeddings").get("float");
        JsonNode values = rows != null && rows.isArray() && !rows.isEmpty() ? rows.get(0) : null;
        if (values == null || !values.isArray() || values.size() != dimensions) {
            throw new EmbeddingException(EmbeddingException.Kind.INVALID_RESPONSE,
                    "Cohere response without " + dimensions + " float values");
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
}
