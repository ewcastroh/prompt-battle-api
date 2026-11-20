package io.github.ewcastroh.promptbattle.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ewcastroh.promptbattle.game.MutableClock;
import io.github.ewcastroh.promptbattle.scoring.EmbeddingException.Kind;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/** Gemini free-tier guard: RPM and TPM over a sliding minute, RPD per Pacific day (PLAN A2, D-013). */
class EmbeddingRateLimiterTest {

    /** Event day, 10:00 in Colombia = 07:00 on 2026-12-02 in Pacific time. */
    static final Instant START = Instant.parse("2026-12-02T15:00:00Z");
    /** The next midnight Pacific after {@link #START} (PST is UTC-8 in December). */
    static final Instant NEXT_PACIFIC_MIDNIGHT = Instant.parse("2026-12-03T08:00:00Z");
    static final Duration MAX_WAIT = Duration.ofSeconds(65);

    private final MutableClock clock = new MutableClock(START);
    private final FakeSleeper sleeper = new FakeSleeper(clock);

    private EmbeddingRateLimiter limiter(int rpm, int tpm, int rpd, int estimatedTokens) {
        return limiter(rpm, tpm, rpd, estimatedTokens, MAX_WAIT);
    }

    private EmbeddingRateLimiter limiter(int rpm, int tpm, int rpd, int estimatedTokens, Duration maxWait) {
        return new EmbeddingRateLimiter(rpm, tpm, rpd, estimatedTokens, maxWait, clock, sleeper);
    }

    @Test
    void theFourthRequestOfTheMinuteWaitsUntilTheFirstLeavesTheWindow() {
        EmbeddingRateLimiter limiter = limiter(3, 0, 0, 267);
        limiter.acquire(); // 15:00:00
        clock.advanceSeconds(5);
        limiter.acquire(); // 15:00:05
        clock.advanceSeconds(5);
        limiter.acquire(); // 15:00:10
        clock.advanceSeconds(10);

        limiter.acquire(); // 15:00:20: three requests in the last minute
        assertThat(sleeper.sleeps).containsExactly(Duration.ofSeconds(40));
        assertThat(clock.instant()).isEqualTo(START.plusSeconds(60));

        limiter.acquire(); // the window slides: the 15:00:05 request leaves at 15:01:05
        assertThat(sleeper.sleeps).containsExactly(Duration.ofSeconds(40), Duration.ofSeconds(5));
        assertThat(limiter.usedToday()).isEqualTo(5);
    }

    @Test
    void eachRequestReservesTheEstimateUntilTheReportedCountCorrectsIt() {
        EmbeddingRateLimiter limiter = limiter(0, 1000, 0, 300);
        EmbeddingRateLimiter.Permit first = limiter.acquire();
        EmbeddingRateLimiter.Permit second = limiter.acquire();
        limiter.acquire();
        assertThat(limiter.hasCapacityNow()).as("900 reserved + 300 > 1000").isFalse();

        limiter.recordTokens(first, 200);
        assertThat(limiter.hasCapacityNow()).as("800 + 300: a count below the estimate keeps the estimate")
                .isFalse();
        limiter.recordTokens(second, 200);
        assertThat(limiter.hasCapacityNow()).as("700 + 300 = 1000").isTrue();

        limiter.acquire();
        assertThat(sleeper.sleeps).isEmpty();
    }

    @Test
    void aReportedCountAboveTheEstimateIsReservedForLaterRequests() {
        EmbeddingRateLimiter limiter = limiter(0, 1000, 0, 100);
        EmbeddingRateLimiter.Permit first = limiter.acquire(); // 15:00:00
        limiter.recordTokens(first, 400); // the image cost 400 tokens, not the 100 estimated
        clock.advanceSeconds(10);
        limiter.acquire(); // 15:00:10, reserves 400: 800 in the window
        assertThat(limiter.hasCapacityNow()).as("800 + 400 > 1000, although 800 + 100 would fit").isFalse();

        clock.advanceSeconds(10);
        limiter.acquire(); // 15:00:20: fits once the first request leaves at 15:01:00 (400 + 400)
        assertThat(sleeper.sleeps).containsExactly(Duration.ofSeconds(40));
    }

    @Test
    void aRequestLargerThanTheTokenLimitStillGoesAlone() {
        EmbeddingRateLimiter limiter = limiter(0, 1000, 0, 1500);
        limiter.acquire();
        assertThat(sleeper.sleeps).isEmpty();

        limiter.acquire(); // waits for an empty window
        assertThat(sleeper.sleeps).containsExactly(Duration.ofSeconds(60));
    }

    @Test
    void theDailyLimitFailsAtOnceWithoutWaiting() {
        EmbeddingRateLimiter limiter = limiter(2, 0, 2, 267);
        limiter.acquire();
        limiter.acquire(); // the day's last request; the minute window is full too

        assertFails(limiter::acquire, Kind.QUOTA_EXHAUSTED);
        assertThat(sleeper.sleeps).as("waiting for the minute window cannot help").isEmpty();
        assertThat(limiter.usedToday()).isEqualTo(2);
        assertThat(limiter.quotaLeft()).isFalse();

        clock.advanceSeconds(60); // the minute window is free again; the day is still used up
        assertThat(limiter.hasCapacityNow()).isFalse();
        assertFails(limiter::acquire, Kind.QUOTA_EXHAUSTED);
    }

    @Test
    void theUsageListenerHearsTheDayCountAndLimitOnEveryGrant() {
        EmbeddingRateLimiter limiter = limiter(2, 0, 3, 267);
        List<String> usage = usageOf(limiter);
        limiter.acquire();
        limiter.acquire();
        limiter.acquire(); // granted after waiting for the minute window: still one report

        assertFails(limiter::acquire, Kind.QUOTA_EXHAUSTED); // a refused request is not reported
        assertThat(sleeper.sleeps).hasSize(1);
        assertThat(usage).containsExactly("2026-12-02 1/3", "2026-12-02 2/3", "2026-12-02 3/3");
    }

    @Test
    void theDayRollsAtMidnightPacificTime() {
        MutableClock clock = new MutableClock(Instant.parse("2026-12-02T07:59:59Z")); // 23:59:59 PST, Dec 1
        EmbeddingRateLimiter limiter = new EmbeddingRateLimiter(0, 0, 2, 267, MAX_WAIT, clock,
                new FakeSleeper(clock));
        List<String> usage = usageOf(limiter);
        limiter.acquire();
        limiter.acquire();
        assertThat(limiter.today()).isEqualTo("2026-12-01");
        assertFails(limiter::acquire, Kind.QUOTA_EXHAUSTED);

        clock.advanceSeconds(1); // 08:00:00Z: midnight PST, 03:00 in Colombia
        assertThat(limiter.today()).isEqualTo("2026-12-02");
        assertThat(limiter.usedToday()).isZero();
        assertThat(limiter.quotaLeft()).isTrue();
        limiter.acquire();
        assertThat(usage).containsExactly("2026-12-01 1/2", "2026-12-01 2/2", "2026-12-02 1/2");
    }

    @Test
    void restoreOnlyAppliesToTodaysCount() {
        EmbeddingRateLimiter limiter = limiter(0, 0, 10, 267);
        limiter.restore("2026-12-01", 9); // the previous Pacific day: its quota has reset since
        assertThat(limiter.usedToday()).isZero();

        limiter.restore("2026-12-02", 6);
        assertThat(limiter.usedToday()).isEqualTo(6);
        limiter.restore("2026-12-02", 4); // never hands back requests already counted
        assertThat(limiter.usedToday()).isEqualTo(6);

        limiter.restore("2026-12-02", 10);
        assertFails(limiter::acquire, Kind.QUOTA_EXHAUSTED);
    }

    @Test
    void aWaitLongerThanMaxWaitFailsAsRateLimitedWithoutWaiting() {
        EmbeddingRateLimiter limiter = limiter(1, 0, 0, 267, Duration.ofSeconds(30));
        limiter.acquire();
        clock.advanceSeconds(20); // the next slot frees in 40 s

        assertFails(limiter::acquire, Kind.RATE_LIMITED);
        assertThat(sleeper.sleeps).isEmpty();
        assertThat(limiter.usedToday()).as("a refused request is not counted").isEqualTo(1);

        clock.advanceSeconds(15); // now 25 s: within maxWait
        limiter.acquire();
        assertThat(sleeper.sleeps).containsExactly(Duration.ofSeconds(25));
    }

    @Test
    void waitsAddUpTowardMaxWait() {
        EmbeddingRateLimiter limiter = limiter(1, 0, 0, 267, Duration.ofSeconds(90));
        limiter.acquire();
        sleeper.duringNextSleep = limiter::acquire; // another request takes the slot the moment it frees

        assertFails(limiter::acquire, Kind.RATE_LIMITED); // 60 s waited + 60 s more > 90 s
        assertThat(sleeper.sleeps).containsExactly(Duration.ofSeconds(60));
        assertThat(limiter.usedToday()).isEqualTo(2);
    }

    @Test
    void hasCapacityNowNeitherWaitsNorCounts() {
        EmbeddingRateLimiter limiter = limiter(2, 0, 0, 267);
        assertThat(limiter.hasCapacityNow()).isTrue();
        limiter.acquire();
        limiter.acquire();

        assertThat(limiter.hasCapacityNow()).as("a third request would wait").isFalse();
        assertThat(limiter.usedToday()).isEqualTo(2);
        assertThat(sleeper.sleeps).isEmpty();

        clock.advanceSeconds(60);
        assertThat(limiter.hasCapacityNow()).isTrue();
    }

    @Test
    void exhaustTodayRefusesEveryRequestUntilMidnightPacific() {
        EmbeddingRateLimiter limiter = limiter(0, 0, 1000, 267);
        List<String> usage = usageOf(limiter);
        limiter.acquire();

        limiter.exhaustToday(); // Gemini answered with a daily-quota 429
        assertThat(usage).containsExactly("2026-12-02 1/1000", "2026-12-02 1000/1000"); // → EMBEDDING_QUOTA_LOW
        assertThat(limiter.usedToday()).isEqualTo(1000);
        assertThat(limiter.quotaLeft()).isFalse();
        assertThat(limiter.hasCapacityNow()).isFalse();
        assertFails(limiter::acquire, Kind.QUOTA_EXHAUSTED);
        assertThat(sleeper.sleeps).isEmpty();

        clock.advance(Duration.between(START, NEXT_PACIFIC_MIDNIGHT));
        assertThat(limiter.quotaLeft()).isTrue();
        limiter.acquire();
        assertThat(usage).containsExactly("2026-12-02 1/1000", "2026-12-02 1000/1000", "2026-12-03 1/1000");
    }

    @Test
    void limitsAtOrBelowZeroAreOff() {
        EmbeddingRateLimiter limiter = limiter(0, 0, 0, 267);
        for (int i = 0; i < 500; i++) {
            limiter.acquire();
        }
        assertThat(sleeper.sleeps).isEmpty();
        assertThat(limiter.usedToday()).isEqualTo(500);
        assertThat(limiter.quotaLeft()).isTrue();
        assertThat(limiter.hasCapacityNow()).isTrue();
    }

    @Test
    void exhaustTodayRefusesRequestsEvenWithoutAClientSideDailyLimit() {
        EmbeddingRateLimiter limiter = limiter(0, 0, 0, 267); // RPD off, e.g. after moving to the paid tier
        limiter.acquire();

        limiter.exhaustToday(); // the provider's own daily quota is gone: stop sending until the reset
        assertThat(limiter.quotaLeft()).isFalse();
        assertThat(limiter.hasCapacityNow()).isFalse();
        assertFails(limiter::acquire, Kind.QUOTA_EXHAUSTED);

        clock.advance(Duration.between(START, NEXT_PACIFIC_MIDNIGHT));
        assertThat(limiter.quotaLeft()).isTrue();
        limiter.acquire();
    }

    /** Records each usage report as "day count/limit". */
    private static List<String> usageOf(EmbeddingRateLimiter limiter) {
        List<String> usage = new ArrayList<>();
        limiter.setListener((day, count, limit) -> usage.add(day + " " + count + "/" + limit));
        return usage;
    }

    private static void assertFails(ThrowingCallable call, Kind kind) {
        assertThatThrownBy(call).isInstanceOfSatisfying(EmbeddingException.class,
                e -> assertThat(e.kind()).isEqualTo(kind));
    }

    /** Moves the test clock instead of sleeping, and records every wait. */
    static final class FakeSleeper implements EmbeddingRateLimiter.Sleeper {

        private final MutableClock clock;
        final List<Duration> sleeps = new ArrayList<>();
        /** Runs once during the next wait, after the clock moved (e.g. a competing request). */
        Runnable duringNextSleep;

        FakeSleeper(MutableClock clock) {
            this.clock = clock;
        }

        @Override
        public void sleep(Duration duration) {
            sleeps.add(duration);
            clock.advance(duration);
            Runnable action = duringNextSleep;
            duringNextSleep = null;
            if (action != null) {
                action.run();
            }
        }
    }
}
