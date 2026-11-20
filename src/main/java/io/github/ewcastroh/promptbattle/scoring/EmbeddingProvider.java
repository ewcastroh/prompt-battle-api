package io.github.ewcastroh.promptbattle.scoring;

/** Image embedding port (SPEC §4). Throws {@link EmbeddingException} on any failure. */
public interface EmbeddingProvider {

    String providerId();

    String modelId();

    /** One vector for one image. Adapters own their timeouts, retries and client-side rate limits. */
    double[] embed(byte[] image, String mimeType);

    /**
     * Best effort: never waits for a client-side rate limit (throws {@link EmbeddingException.Skipped} instead)
     * and never retries. Used by prefetching, which must not take capacity scoring is waiting for.
     */
    default double[] embedWithoutWaiting(byte[] image, String mimeType) {
        return embed(image, mimeType);
    }

    /** The provider's client-side rate limiter (Gemini free tier, D-013), or null when it has none. */
    default EmbeddingRateLimiter rateLimiter() {
        return null;
    }

    /** False when a call now would wait for a client-side rate limit or the daily quota is used up. */
    default boolean hasCapacityNow() {
        EmbeddingRateLimiter limiter = rateLimiter();
        return limiter == null || limiter.hasCapacityNow();
    }

    /** False once the provider's daily quota is used up (shown as unhealthy in admin). */
    default boolean quotaLeft() {
        EmbeddingRateLimiter limiter = rateLimiter();
        return limiter == null || limiter.quotaLeft();
    }
}
