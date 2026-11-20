package io.github.ewcastroh.promptbattle.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** SPEC §5.4, PLAN A2 RetryPolicy: one retry with jittered backoff, the server's hint as a floor, the deadline. */
class RetryPolicyTest {

    private static Deadline far() {
        return Deadline.after(Duration.ofMinutes(5));
    }

    /** A policy whose jitter source always returns {@code random} (0.5 = no jitter). */
    private static RetryPolicy policy(int maxRetries, long baseMs, long capMs, double random) {
        return new RetryPolicy(maxRetries, Duration.ofMillis(baseMs), Duration.ofMillis(capMs), () -> random);
    }

    @Test
    void theStandardPolicyRetriesOnceAfterAJitteredHalfSecond() {
        assertThat(RetryPolicy.STANDARD.maxRetries()).isEqualTo(1);
        for (int i = 0; i < 50; i++) {
            assertThat(RetryPolicy.STANDARD.delay(1, null, far()))
                    .isBetween(Duration.ofMillis(250), Duration.ofMillis(750));
        }
        assertThat(RetryPolicy.STANDARD.delay(2, null, far())).isNull();
        assertThat(RetryPolicy.STANDARD.delay(0, null, far())).isNull();
    }

    @Test
    void backoffDoublesWithEachRetry() {
        RetryPolicy policy = policy(3, 500, 60_000, 0.5);

        assertThat(policy.delay(1, null, far())).isEqualTo(Duration.ofMillis(500));
        assertThat(policy.delay(2, null, far())).isEqualTo(Duration.ofMillis(1000));
        assertThat(policy.delay(3, null, far())).isEqualTo(Duration.ofMillis(2000));
        assertThat(policy.delay(4, null, far())).isNull();
    }

    @Test
    void jitterSpansHalfToOneAndAHalfTimesTheBackoff() {
        assertThat(policy(1, 1000, 60_000, 0.0).delay(1, null, far())).isEqualTo(Duration.ofMillis(500));
        assertThat(policy(1, 1000, 60_000, 0.75).delay(1, null, far())).isEqualTo(Duration.ofMillis(1250));
    }

    @Test
    void backoffIsCappedButTheServersHintIsNot() {
        RetryPolicy policy = policy(2, 1000, 1500, 0.5);

        assertThat(policy.delay(2, null, far())).isEqualTo(Duration.ofMillis(1500));
        assertThat(policy.delay(1, Duration.ofSeconds(4), far())).as("Retry-After wins")
                .isEqualTo(Duration.ofSeconds(4));
        assertThat(policy.delay(1, Duration.ofMillis(200), far())).as("a shorter hint keeps the backoff")
                .isEqualTo(Duration.ofMillis(1000));
    }

    @Test
    void noRetryThatWouldLeaveTooLittleTimeBeforeTheDeadline() {
        RetryPolicy policy = policy(1, 500, 8000, 0.5);

        assertThat(policy.delay(1, null, Deadline.after(Duration.ofMillis(900)))).as("400 ms would be left").isNull();
        assertThat(policy.delay(1, null, Deadline.after(Duration.ZERO))).isNull();
        assertThat(policy.delay(1, Duration.ofSeconds(10), Deadline.after(Duration.ofSeconds(10)))).isNull();
        assertThat(policy.delay(1, Duration.ofSeconds(5), Deadline.after(Duration.ofSeconds(30))))
                .isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void theImmediatePolicyKeepsTheRetryCountWithoutWaiting() {
        RetryPolicy policy = RetryPolicy.immediate(1);

        assertThat(policy.maxRetries()).isEqualTo(1);
        assertThat(policy.delay(1, null, far())).isEqualTo(Duration.ZERO);
        assertThat(policy.delay(2, null, far())).isNull();
        assertThat(policy.delay(1, Duration.ofSeconds(2), far())).as("the server's hint still counts")
                .isEqualTo(Duration.ofSeconds(2));
    }
}
