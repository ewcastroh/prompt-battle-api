package io.github.ewcastroh.promptbattle.scoring;

/** An embedding call that produced no vector. {@link #kind()} drives retries, failover and the probe report. */
public class EmbeddingException extends RuntimeException {

    public enum Kind {
        /** Per-minute limit (ours or the provider's): worth one retry later. */
        RATE_LIMITED,
        /** Daily quota used up: no retry today, fail over. */
        QUOTA_EXHAUSTED,
        TIMEOUT,
        PROVIDER_ERROR,
        INVALID_RESPONSE
    }

    private final Kind kind;

    public EmbeddingException(String message) {
        this(Kind.PROVIDER_ERROR, message);
    }

    public EmbeddingException(String message, Throwable cause) {
        super(message, cause);
        this.kind = Kind.PROVIDER_ERROR;
    }

    public EmbeddingException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    /**
     * A best-effort call (prefetch) gave up without asking the provider: no capacity right now. Not a provider
     * failure; a scoring call that shared it simply does the call itself.
     */
    public static final class Skipped extends EmbeddingException {
        public Skipped(String message) {
            super(Kind.RATE_LIMITED, message);
        }
    }
}
