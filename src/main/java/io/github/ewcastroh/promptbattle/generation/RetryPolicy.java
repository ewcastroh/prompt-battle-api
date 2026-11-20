package io.github.ewcastroh.promptbattle.generation;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * One automatic retry with jittered exponential backoff for 429, 5xx and connection errors (SPEC §5.4),
 * honoring the server's hint (Retry-After, fal's X-Fal-Needs-Retry, Gemini's RetryInfo), and never past the
 * call's deadline. Applied per HTTP call: a poll that fails is retried, a successful submit is not repeated.
 */
public final class RetryPolicy {

    /** Time a retried call needs at least; a retry that would leave less is not attempted. */
    public static final Duration MIN_CALL = Duration.ofMillis(500);

    public static final RetryPolicy STANDARD = new RetryPolicy(1, Duration.ofMillis(500), Duration.ofSeconds(8),
            () -> ThreadLocalRandom.current().nextDouble());

    private final int maxRetries;
    private final Duration base;
    private final Duration cap;
    private final DoubleSupplier random;

    public RetryPolicy(int maxRetries, Duration base, Duration cap, DoubleSupplier random) {
        this.maxRetries = maxRetries;
        this.base = base;
        this.cap = cap;
        this.random = random;
    }

    /** A policy with the same retry count and no waiting (tests). */
    public static RetryPolicy immediate(int maxRetries) {
        return new RetryPolicy(maxRetries, Duration.ZERO, Duration.ZERO, () -> 0.5);
    }

    public int maxRetries() {
        return maxRetries;
    }

    /**
     * The wait before retry number {@code retry} (1-based), or null when no retry is left or it would not fit
     * before the deadline. Backoff is {@code base × 2^(retry-1)} with ±50 % jitter, capped, but never shorter
     * than the server's hint.
     */
    public Duration delay(int retry, Duration hint, Deadline deadline) {
        if (retry < 1 || retry > maxRetries) {
            return null;
        }
        long backoff = base.toMillis() << Math.min(20, retry - 1);
        long millis = Math.min(cap.toMillis(), (long) (backoff * (0.5 + random.getAsDouble())));
        if (hint != null) {
            millis = Math.max(millis, hint.toMillis());
        }
        Duration delay = Duration.ofMillis(millis);
        if (deadline.remaining().minus(delay).compareTo(MIN_CALL) < 0) {
            return null;
        }
        return delay;
    }
}
