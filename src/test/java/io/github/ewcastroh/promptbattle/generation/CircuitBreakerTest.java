package io.github.ewcastroh.promptbattle.generation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ewcastroh.promptbattle.game.CircuitState;
import io.github.ewcastroh.promptbattle.game.FailureKind;
import io.github.ewcastroh.promptbattle.game.MutableClock;
import io.github.ewcastroh.promptbattle.generation.CircuitBreaker.Role;
import io.github.ewcastroh.promptbattle.generation.CircuitBreaker.Transition;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** SPEC §5.4, PLAN A2: 3 failures in 30 s open the circuit for 60 s, one trial decides; SAFETY_BLOCKED never counts. */
class CircuitBreakerTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-12-02T15:00:00Z"));
    private final CircuitBreaker breaker = new CircuitBreaker(clock);
    /** Transitions the registry's listener heard, e.g. {@code opened:fal}. */
    private final List<String> transitions = new CopyOnWriteArrayList<>();

    private Transition fail() {
        return breaker.record(true, Role.NORMAL);
    }

    private void open() {
        fail();
        fail();
        assertThat(fail()).isEqualTo(Transition.OPENED);
    }

    private void halfOpen() {
        open();
        clock.advance(CircuitBreaker.OPEN_FOR);
        assertThat(breaker.state()).isEqualTo(CircuitState.HALF_OPEN);
    }

    @Test
    void threeFailuresWithin30SecondsOpenTheCircuit() {
        assertThat(breaker.state()).isEqualTo(CircuitState.CLOSED);
        assertThat(fail()).isEqualTo(Transition.NONE);
        clock.advanceSeconds(10);
        assertThat(fail()).isEqualTo(Transition.NONE);
        clock.advanceSeconds(19);
        assertThat(breaker.allows()).isTrue();

        assertThat(fail()).isEqualTo(Transition.OPENED);

        assertThat(breaker.state()).isEqualTo(CircuitState.OPEN);
        assertThat(breaker.allows()).isFalse();
    }

    @Test
    void failuresOlderThan30SecondsNoLongerCount() {
        fail();
        fail();
        clock.advanceSeconds(31);

        assertThat(fail()).isEqualTo(Transition.NONE);
        assertThat(breaker.state()).isEqualTo(CircuitState.CLOSED);
        assertThat(breaker.allows()).isTrue();
        // the window slides: that late failure counts together with the next two
        assertThat(fail()).isEqualTo(Transition.NONE);
        assertThat(fail()).isEqualTo(Transition.OPENED);
    }

    @Test
    void anOpenCircuitTurnsHalfOpenAfter60Seconds() {
        open();
        clock.advanceSeconds(59);
        assertThat(breaker.state()).isEqualTo(CircuitState.OPEN);
        assertThat(breaker.allows()).isFalse();

        clock.advanceSeconds(1);

        assertThat(breaker.state()).isEqualTo(CircuitState.HALF_OPEN);
        assertThat(breaker.allows()).isTrue();
    }

    @Test
    void halfOpenAllowsExactlyOneTrialAtATime() {
        halfOpen();

        assertThat(breaker.onDispatch(false)).isEqualTo(Role.TRIAL);

        assertThat(breaker.allows()).isFalse();
        assertThat(breaker.state()).isEqualTo(CircuitState.HALF_OPEN);
        assertThat(breaker.onDispatch(false)).as("no second trial while one is in flight").isNotEqualTo(Role.TRIAL);
        assertThat(breaker.allows()).isFalse();
    }

    @Test
    void aSuccessfulTrialClosesTheCircuit() {
        halfOpen();
        Role trial = breaker.onDispatch(false);

        assertThat(breaker.record(false, trial)).isEqualTo(Transition.CLOSED);

        assertThat(breaker.state()).isEqualTo(CircuitState.CLOSED);
        assertThat(breaker.allows()).isTrue();
        fail();
        fail();
        assertThat(breaker.state()).as("a fresh failure window").isEqualTo(CircuitState.CLOSED);
    }

    @Test
    void aFailedTrialOpensTheCircuitAgainWithoutASecondOpenedTransition() {
        halfOpen();
        Role trial = breaker.onDispatch(false);
        clock.advanceSeconds(5);

        assertThat(breaker.record(true, trial)).isEqualTo(Transition.NONE);

        assertThat(breaker.state()).isEqualTo(CircuitState.OPEN);
        assertThat(breaker.allows()).isFalse();
        clock.advanceSeconds(59);
        assertThat(breaker.state()).as("a new open period from the failed trial").isEqualTo(CircuitState.OPEN);
        clock.advanceSeconds(1);
        assertThat(breaker.state()).isEqualTo(CircuitState.HALF_OPEN);
        assertThat(breaker.onDispatch(false)).isEqualTo(Role.TRIAL);
    }

    @Test
    void normalResultsWhileOpenOrHalfOpenAreIgnored() {
        open();
        // late results of calls dispatched before the circuit opened
        assertThat(breaker.record(false, Role.NORMAL)).isEqualTo(Transition.NONE);
        assertThat(breaker.record(true, Role.NORMAL)).isEqualTo(Transition.NONE);
        assertThat(breaker.state()).isEqualTo(CircuitState.OPEN);

        clock.advance(CircuitBreaker.OPEN_FOR);
        assertThat(breaker.state()).as("late results did not extend the open period").isEqualTo(CircuitState.HALF_OPEN);
        assertThat(breaker.record(false, Role.NORMAL)).isEqualTo(Transition.NONE);
        assertThat(breaker.record(true, Role.NORMAL)).isEqualTo(Transition.NONE);
        assertThat(breaker.state()).isEqualTo(CircuitState.HALF_OPEN);
        assertThat(breaker.allows()).as("the trial is still to come").isTrue();

        Role trial = breaker.onDispatch(false);
        assertThat(breaker.record(true, Role.NORMAL)).isEqualTo(Transition.NONE);
        assertThat(breaker.state()).isEqualTo(CircuitState.HALF_OPEN);
        assertThat(breaker.allows()).isFalse();
        assertThat(breaker.record(false, trial)).isEqualTo(Transition.CLOSED);
    }

    @Test
    void aProbeSuccessClosesAnOpenCircuit() {
        open();
        clock.advanceSeconds(10);
        Role probe = breaker.onDispatch(true);
        assertThat(probe).isEqualTo(Role.PROBE);

        assertThat(breaker.record(false, probe)).isEqualTo(Transition.CLOSED);

        assertThat(breaker.state()).isEqualTo(CircuitState.CLOSED);
        assertThat(breaker.allows()).isTrue();
    }

    @Test
    void resetClosesTheCircuitAndForgetsEarlierFailures() {
        open();

        assertThat(breaker.reset()).isEqualTo(Transition.CLOSED);

        assertThat(breaker.state()).isEqualTo(CircuitState.CLOSED);
        assertThat(breaker.allows()).isTrue();
        fail();
        fail();
        assertThat(breaker.reset()).as("already closed: nothing to report").isEqualTo(Transition.NONE);
        fail();
        fail();
        assertThat(breaker.state()).isEqualTo(CircuitState.CLOSED);
        assertThat(fail()).isEqualTo(Transition.OPENED);
    }

    // ------------------------------------------------------------------ through ProviderRegistry

    private ProviderRegistry registry() {
        ProviderRegistry registry = new ProviderRegistry(
                List.of(new GatedImageGenerator("fal", 2), new GatedImageGenerator("bfl", 3)), List.of("fal", "bfl"),
                List.of("fal", "bfl"), clock);
        registry.setListener(new ProviderRegistry.Listener() {
            @Override
            public void circuitOpened(String providerId) {
                transitions.add("opened:" + providerId);
            }

            @Override
            public void circuitClosed(String providerId) {
                transitions.add("closed:" + providerId);
            }
        });
        return registry;
    }

    private static void record(ProviderRegistry registry, FailureKind kind) {
        registry.record("fal", new GenerationResult.Failure(kind, "test"), 10, Role.NORMAL);
    }

    @ParameterizedTest
    @EnumSource(value = FailureKind.class, names = "SAFETY_BLOCKED", mode = EnumSource.Mode.EXCLUDE)
    void everyFailureKindButSafetyBlockedCounts(FailureKind kind) {
        ProviderRegistry registry = registry();

        for (int i = 0; i < 3; i++) {
            record(registry, kind);
        }

        assertThat(registry.circuit("fal")).isEqualTo(CircuitState.OPEN);
        assertThat(registry.allows("fal")).isFalse();
        assertThat(transitions).containsExactly("opened:fal");
        assertThat(registry.stats("fal").failureCount()).isEqualTo(3);
        assertThat(registry.stats("fal").lastErrorKind()).isEqualTo(kind);
    }

    @Test
    void safetyBlocksNeverOpenTheCircuit() {
        ProviderRegistry registry = registry();

        for (int i = 0; i < 10; i++) {
            record(registry, FailureKind.SAFETY_BLOCKED);
        }

        assertThat(registry.circuit("fal")).isEqualTo(CircuitState.CLOSED);
        assertThat(transitions).isEmpty();
        assertThat(registry.stats("fal").failureCount()).isZero();
        assertThat(registry.stats("fal").lastErrorKind()).isNull();
        // between real failures they are not counted either: only the third real failure opens it
        record(registry, FailureKind.PROVIDER_ERROR);
        record(registry, FailureKind.SAFETY_BLOCKED);
        record(registry, FailureKind.PROVIDER_ERROR);
        record(registry, FailureKind.SAFETY_BLOCKED);
        assertThat(registry.circuit("fal")).isEqualTo(CircuitState.CLOSED);
        record(registry, FailureKind.PROVIDER_ERROR);
        assertThat(registry.circuit("fal")).isEqualTo(CircuitState.OPEN);
    }

    @Test
    void theListenerHearsOneOpenedAndOneClosedAcrossAFailedTrial() {
        ProviderRegistry registry = registry();
        for (int i = 0; i < 3; i++) {
            record(registry, FailureKind.TIMEOUT);
        }
        clock.advance(CircuitBreaker.OPEN_FOR);
        Role trial = registry.onDispatch("fal", false);
        assertThat(trial).isEqualTo(Role.TRIAL);
        registry.record("fal", new GenerationResult.Failure(FailureKind.TIMEOUT, "still down"), 10, trial);
        assertThat(registry.circuit("fal")).isEqualTo(CircuitState.OPEN);

        clock.advance(CircuitBreaker.OPEN_FOR);
        trial = registry.onDispatch("fal", false);
        registry.record("fal", new GenerationResult.Success(new byte[] {1}, "image/jpeg", "fal-model", 10), 10, trial);

        assertThat(registry.circuit("fal")).isEqualTo(CircuitState.CLOSED);
        assertThat(transitions).containsExactly("opened:fal", "closed:fal");
    }

    @Test
    void registryResetClosesAnOpenCircuitAndReportsItOnce() {
        ProviderRegistry registry = registry();
        for (int i = 0; i < 3; i++) {
            record(registry, FailureKind.PROVIDER_ERROR);
        }

        registry.reset("fal");
        registry.reset("fal");

        assertThat(registry.circuit("fal")).isEqualTo(CircuitState.CLOSED);
        assertThat(registry.allows("fal")).isTrue();
        assertThat(transitions).containsExactly("opened:fal", "closed:fal");
    }

    @Test
    void anEventResetClosesEveryCircuitAndClearsTheCountersButKeepsTheActiveProvider() {
        ProviderRegistry registry = registry();
        registry.record("fal", new GenerationResult.Success(new byte[] {1}, "image/jpeg", "fal-model", 1200), 1200,
                Role.NORMAL);
        for (int i = 0; i < 3; i++) {
            record(registry, FailureKind.TIMEOUT);
        }
        registry.setActive("bfl"); // the domain failed over
        assertThat(registry.stats("fal").p50Ms()).isEqualTo(1200L);

        registry.resetAll();

        assertThat(registry.circuit("fal")).isEqualTo(CircuitState.CLOSED);
        assertThat(registry.circuit("bfl")).isEqualTo(CircuitState.CLOSED);
        ProviderRegistry.Stats fal = registry.stats("fal");
        assertThat(fal.successCount()).isZero();
        assertThat(fal.failureCount()).isZero();
        assertThat(fal.p50Ms()).isNull();
        assertThat(fal.p95Ms()).isNull();
        assertThat(fal.lastErrorKind()).isNull();
        assertThat(fal.lastErrorAt()).isNull();
        assertThat(registry.active()).isEqualTo("bfl");
        assertThat(transitions).containsExactly("opened:fal", "closed:fal");
    }
}
