package io.github.ewcastroh.promptbattle.scoring;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;

/**
 * Client-side limits under the Gemini free tier (D-013): a sliding one-minute window of requests (RPM) and
 * tokens (TPM), and a daily request counter (RPD) that resets at midnight Pacific time. Each request reserves
 * an estimated token count, corrected with the provider's reported usage once the response arrives. Every
 * request sent counts toward the day, failed ones included. A limit ≤ 0 is off. Thread-safe.
 */
public final class EmbeddingRateLimiter {

    public static final ZoneId RESET_ZONE = ZoneId.of("America/Los_Angeles");
    static final Duration WINDOW = Duration.ofMinutes(1);

    /** Receives the day's request count after every granted request (persisted and alerted by the domain). */
    @FunctionalInterface
    public interface UsageListener {
        void used(String day, int count, int limit);

        UsageListener NONE = (day, count, limit) -> {
        };
    }

    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;

        Sleeper REAL = Thread::sleep;
    }

    /** A granted request; its token count can be corrected with {@link #recordTokens}. */
    public static final class Permit {
        final Instant at;
        int tokens;

        Permit(Instant at, int tokens) {
            this.at = at;
            this.tokens = tokens;
        }
    }

    private final int rpm;
    private final int tpm;
    private final int rpd;
    private final int estimatedTokens;
    private final Duration maxWait;
    private final Clock clock;
    private final Sleeper sleeper;
    private final ArrayDeque<Permit> window = new ArrayDeque<>();
    private long windowTokens;
    /** Last token count the provider reported; reservations use it once known (images may cost more). */
    private int observedTokens;
    private String day;
    private int used;
    /** The provider reported today's quota as used up; holds even when the client-side daily limit is off. */
    private boolean exhausted;
    /** No request before this instant: the provider answered a per-minute 429 (its window, not ours). */
    private Instant pausedUntil = Instant.MIN;
    private volatile UsageListener listener = UsageListener.NONE;

    /**
     * @param estimatedTokens tokens reserved per request until the real count is known
     * @param maxWait         longest a request waits for the minute window before failing with RATE_LIMITED
     */
    public EmbeddingRateLimiter(int rpm, int tpm, int rpd, int estimatedTokens, Duration maxWait, Clock clock,
            Sleeper sleeper) {
        this.rpm = rpm;
        this.tpm = tpm;
        this.rpd = rpd;
        this.estimatedTokens = Math.max(1, estimatedTokens);
        this.maxWait = maxWait;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    public void setListener(UsageListener listener) {
        this.listener = listener == null ? UsageListener.NONE : listener;
    }

    /** Waits until the request fits the minute window (at most {@code maxWait}) and counts it for the day. */
    public Permit acquire() {
        return acquire(maxWait);
    }

    /** Like {@link #acquire()} but waits at most {@code limit}; RATE_LIMITED (no permit taken) when it can't. */
    public Permit acquire(Duration limit) {
        Duration waited = Duration.ZERO;
        while (true) {
            Permit permit = null;
            Duration wait;
            String today;
            int count;
            synchronized (this) {
                Instant now = clock.instant();
                rollDay(now);
                prune(now);
                if (!quotaLeftToday()) {
                    throw new EmbeddingException(EmbeddingException.Kind.QUOTA_EXHAUSTED,
                            "Daily embedding quota used: " + used + "/" + rpd);
                }
                Duration paused = now.isBefore(pausedUntil) ? Duration.between(now, pausedUntil) : Duration.ZERO;
                if (paused.isZero() && fits()) {
                    permit = grant(now);
                    wait = Duration.ZERO;
                } else {
                    wait = fits() ? paused : max(paused, timeUntilFree(now));
                }
                today = day;
                count = used;
            }
            if (permit != null) {
                listener.used(today, count, rpd);
                return permit;
            }
            if (waited.plus(wait).compareTo(limit) > 0) {
                throw new EmbeddingException(EmbeddingException.Kind.RATE_LIMITED,
                        "Embedding rate limit: next slot in " + wait.toMillis() + " ms");
            }
            try {
                sleeper.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new EmbeddingException(EmbeddingException.Kind.TIMEOUT, "Interrupted waiting for the rate limit");
            }
            waited = waited.plus(wait);
        }
    }

    /**
     * A permit only if one is free right now (no wait, nothing counted otherwise): best-effort work such as
     * prefetching never waits for the limit. Null when it would have to wait or today's quota is gone.
     */
    public Permit tryAcquire() {
        Permit permit;
        String today;
        int count;
        synchronized (this) {
            Instant now = clock.instant();
            rollDay(now);
            prune(now);
            if (!quotaLeftToday() || now.isBefore(pausedUntil) || !fits()) {
                return null;
            }
            permit = grant(now);
            today = day;
            count = used;
        }
        listener.used(today, count, rpd);
        return permit;
    }

    /** The provider answered a per-minute 429: hold every request for {@code duration} (its hint). */
    public synchronized void pauseFor(Duration duration) {
        Instant until = clock.instant().plus(duration);
        if (until.isAfter(pausedUntil)) {
            pausedUntil = until;
        }
    }

    private Permit grant(Instant now) {
        Permit permit = new Permit(now, reservation());
        window.addLast(permit);
        windowTokens += permit.tokens;
        used++;
        return permit;
    }

    private static Duration max(Duration a, Duration b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    /** Replaces the reserved estimate with the provider's reported token count. */
    public synchronized void recordTokens(Permit permit, int tokens) {
        if (tokens <= 0 || permit == null) {
            return;
        }
        if (window.contains(permit)) {
            windowTokens += tokens - permit.tokens;
        }
        permit.tokens = tokens;
        observedTokens = tokens;
    }

    private int reservation() {
        return Math.max(estimatedTokens, observedTokens);
    }

    /** Whether a request now would be granted without waiting. */
    public synchronized boolean hasCapacityNow() {
        Instant now = clock.instant();
        rollDay(now);
        prune(now);
        return quotaLeftToday() && !now.isBefore(pausedUntil) && fits();
    }

    public synchronized boolean quotaLeft() {
        rollDay(clock.instant());
        return quotaLeftToday();
    }

    private boolean quotaLeftToday() {
        return !exhausted && (rpd <= 0 || used < rpd);
    }

    /** The provider reported the daily quota as exhausted: stop sending until the reset. */
    public void exhaustToday() {
        String today;
        int count;
        synchronized (this) {
            rollDay(clock.instant());
            used = Math.max(used, rpd);
            exhausted = true;
            today = day;
            count = used;
        }
        listener.used(today, count, rpd);
    }

    /** Restores the persisted count after a restart (ignored when it belongs to another day). */
    public synchronized void restore(String persistedDay, int count) {
        rollDay(clock.instant());
        if (day.equals(persistedDay)) {
            used = Math.max(used, count);
        }
    }

    public synchronized int usedToday() {
        rollDay(clock.instant());
        return used;
    }

    public synchronized String today() {
        rollDay(clock.instant());
        return day;
    }

    public int dailyLimit() {
        return rpd;
    }

    private boolean fits() {
        if (window.isEmpty()) {
            return true; // a single request larger than the token limit must still be able to go
        }
        return (rpm <= 0 || window.size() < rpm) && (tpm <= 0 || windowTokens + reservation() <= tpm);
    }

    private Duration timeUntilFree(Instant now) {
        Duration d = Duration.between(now, window.peekFirst().at.plus(WINDOW));
        return d.toMillis() < 1 ? Duration.ofMillis(1) : d;
    }

    private void prune(Instant now) {
        Instant cutoff = now.minus(WINDOW);
        while (!window.isEmpty() && !window.peekFirst().at.isAfter(cutoff)) {
            windowTokens -= window.removeFirst().tokens;
        }
    }

    private void rollDay(Instant now) {
        String today = LocalDate.ofInstant(now, RESET_ZONE).toString();
        if (!today.equals(day)) {
            day = today;
            used = 0;
            exhausted = false;
        }
    }
}
