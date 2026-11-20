package io.github.ewcastroh.promptbattle.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.ewcastroh.promptbattle.game.CircuitState;
import io.github.ewcastroh.promptbattle.game.FailureKind;
import io.github.ewcastroh.promptbattle.game.MutableClock;
import io.github.ewcastroh.promptbattle.generation.GenerationDispatcher.Dispatched;
import io.github.ewcastroh.promptbattle.generation.GenerationDispatcher.Target;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.awaitility.core.ThrowingRunnable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** SPEC §5.4-5.5, D-012, PLAN A2 LoadSplitTest: overflow only to listed providers, pinned jobs, circuits. */
class LoadSplitTest {

    private static final Duration TIMEOUT = Duration.ofMillis(800);
    /** Longer than one periodic re-scan of the dispatcher: a job that must wait had every chance to start. */
    private static final Duration QUIET = Duration.ofMillis(GenerationDispatcher.RECHECK_MILLIS + 50);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-12-02T15:00:00Z"));
    private final GatedImageGenerator fal = new GatedImageGenerator("fal", 2);
    private final GatedImageGenerator bfl = new GatedImageGenerator("bfl", 3);
    private final GatedImageGenerator other = new GatedImageGenerator("other", 5);
    /** The provider each job's start callback received. */
    private final Map<String, String> startedOn = new ConcurrentHashMap<>();
    /** Circuit transitions the registry's listener heard, e.g. {@code opened:fal}. */
    private final List<String> transitions = new CopyOnWriteArrayList<>();
    private ProviderRegistry registry;
    private GenerationDispatcher dispatcher;

    /** Chain fal, bfl, other (fal active) with the given IMAGE_LOAD_SPLIT. */
    private void startDispatcher(String... loadSplit) {
        registry = new ProviderRegistry(List.of(fal, bfl, other), List.of("fal", "bfl", "other"), List.of(loadSplit),
                clock);
        registry.setListener(new ProviderRegistry.Listener() {
            @Override
            public void circuitOpened(String providerId) {
                heard("opened", providerId);
            }

            @Override
            public void circuitClosed(String providerId) {
                heard("closed", providerId);
            }
        });
        dispatcher = new GenerationDispatcher(registry);
    }

    /**
     * Records a transition. The listener must run outside the dispatcher's and the breaker's locks (it posts to
     * the game engine and wakes the dispatcher), so another thread must be able to take both right now.
     */
    private void heard(String transition, String providerId) {
        String event = transition + ":" + providerId;
        try {
            CompletableFuture.runAsync(() -> {
                dispatcher.length();
                registry.circuit(providerId);
            }).get(500, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            event += " (inside a lock)";
        }
        transitions.add(event);
    }

    @AfterEach
    void close() {
        releaseAll();
        if (dispatcher != null) {
            dispatcher.close();
        }
    }

    private void releaseAll() {
        List.of(fal, bfl, other).forEach(GatedImageGenerator::releaseAll);
    }

    private CompletableFuture<Dispatched> attempt(String id) {
        return dispatcher.submit(id, Target.FLEXIBLE, GenerationRequest.square(id, 7), p -> startedOn.put(id, p));
    }

    private Map<String, CompletableFuture<Dispatched>> attempts(String... ids) {
        Map<String, CompletableFuture<Dispatched>> jobs = new LinkedHashMap<>();
        for (String id : ids) {
            jobs.put(id, attempt(id));
        }
        return jobs;
    }

    private CompletableFuture<Dispatched> pinned(String id, String providerId, boolean probe) {
        return dispatcher.submit(id, new Target.Pinned(providerId, probe), GenerationRequest.square(id, 7),
                p -> startedOn.put(id, p));
    }

    private void openCircuit(String providerId) {
        for (int i = 0; i < CircuitBreaker.FAILURES; i++) {
            registry.record(providerId, new GenerationResult.Failure(FailureKind.PROVIDER_ERROR, "down"), 10,
                    CircuitBreaker.Role.NORMAL);
        }
        assertThat(registry.circuit(providerId)).isEqualTo(CircuitState.OPEN);
    }

    private static Dispatched done(CompletableFuture<Dispatched> job) {
        assertThat(job).succeedsWithin(TIMEOUT);
        return job.join();
    }

    private static Map<String, String> providersOf(Map<String, CompletableFuture<Dispatched>> jobs) {
        Map<String, String> providers = new HashMap<>();
        jobs.forEach((id, job) -> providers.put(id, done(job).providerId()));
        return providers;
    }

    private static void stays(ThrowingRunnable assertion) {
        await().during(QUIET).atMost(QUIET.plus(TIMEOUT)).pollInterval(Duration.ofMillis(10)).untilAsserted(assertion);
    }

    // ------------------------------------------------------------------ load split

    @Test
    void attemptsFillTheActiveProviderThenOverflowToTheNextListedOneAndOtherwiseWait() {
        startDispatcher("fal", "bfl");
        Map<String, CompletableFuture<Dispatched>> jobs = attempts("j1", "j2", "j3", "j4", "j5", "j6");

        fal.awaitCalls(2);
        bfl.awaitCalls(3);
        assertThat(fal.calls()).containsExactlyInAnyOrder("j1", "j2");
        assertThat(bfl.calls()).containsExactlyInAnyOrder("j3", "j4", "j5");
        // 'other' is in the chain, closed and idle, but not listed in IMAGE_LOAD_SPLIT: the 6th attempt waits
        stays(() -> {
            assertThat(dispatcher.positionOf("j6")).isEqualTo(1);
            assertThat(other.calls()).isEmpty();
        });
        assertThat(registry.circuit("other")).isEqualTo(CircuitState.CLOSED);

        fal.release("j1"); // the first slot that frees takes it
        fal.awaitCalls(3);
        assertThat(fal.calls().getLast()).isEqualTo("j6");

        releaseAll();
        Map<String, String> providers = providersOf(jobs);
        assertThat(providers).isEqualTo(
                Map.of("j1", "fal", "j2", "fal", "j3", "bfl", "j4", "bfl", "j5", "bfl", "j6", "fal"));
        assertThat(startedOn).isEqualTo(providers);
        assertThat(other.calls()).isEmpty();
    }

    @Test
    void withBflActiveAttemptsFillBflFirstThenOverflowToFal() {
        startDispatcher("fal", "bfl");
        registry.setActive("bfl");
        Map<String, CompletableFuture<Dispatched>> jobs = attempts("j1", "j2", "j3", "j4", "j5", "j6");

        bfl.awaitCalls(3);
        fal.awaitCalls(2);
        assertThat(bfl.calls()).containsExactlyInAnyOrder("j1", "j2", "j3");
        assertThat(fal.calls()).containsExactlyInAnyOrder("j4", "j5");
        stays(() -> {
            assertThat(dispatcher.positionOf("j6")).isEqualTo(1);
            assertThat(other.calls()).isEmpty();
        });

        bfl.release("j2");
        bfl.awaitCalls(4);
        assertThat(bfl.calls().getLast()).isEqualTo("j6");
        releaseAll();
        assertThat(providersOf(jobs)).isEqualTo(
                Map.of("j1", "bfl", "j2", "bfl", "j3", "bfl", "j4", "fal", "j5", "fal", "j6", "bfl"));
    }

    @Test
    void withoutALoadSplitOnlyTheActiveProviderRunsAttempts() {
        startDispatcher();
        Map<String, CompletableFuture<Dispatched>> jobs = attempts("j1", "j2", "j3", "j4");

        fal.awaitCalls(2);
        stays(() -> {
            assertThat(fal.calls()).containsExactlyInAnyOrder("j1", "j2");
            assertThat(dispatcher.length()).isEqualTo(2);
            assertThat(bfl.calls()).isEmpty();
            assertThat(other.calls()).isEmpty();
        });

        fal.release("j1");
        fal.awaitCalls(3);
        assertThat(fal.calls().getLast()).isEqualTo("j3");
        releaseAll();
        assertThat(providersOf(jobs).values()).containsOnly("fal");
    }

    @Test
    void anActiveProviderOutsideTheSplitRunsEveryAttemptAlone() {
        startDispatcher("fal", "bfl");
        registry.setActive("other");
        attempts("j1", "j2", "j3", "j4", "j5", "j6", "j7");

        other.awaitCalls(5);
        stays(() -> {
            assertThat(other.calls()).containsExactlyInAnyOrder("j1", "j2", "j3", "j4", "j5");
            assertThat(dispatcher.positionOf("j6")).isEqualTo(1);
            assertThat(dispatcher.positionOf("j7")).isEqualTo(2);
            assertThat(fal.calls()).isEmpty();
            assertThat(bfl.calls()).isEmpty();
        });
    }

    // ------------------------------------------------------------------ pinned jobs

    @Test
    void aPinnedJobWaitsForItsProviderWhileLaterAttemptsStartElsewhere() {
        startDispatcher("fal", "bfl");
        attempts("f1", "f2");
        CompletableFuture<Dispatched> anchor = pinned("anchor", "fal", false);
        attempts("f3", "f4", "f5", "f6");

        fal.awaitCalls(2);
        bfl.awaitCalls(3);
        assertThat(bfl.calls()).as("attempts queued after the pinned job").containsExactlyInAnyOrder("f3", "f4", "f5");
        stays(() -> {
            assertThat(fal.calls()).containsExactlyInAnyOrder("f1", "f2");
            assertThat(dispatcher.positionOf("anchor")).isEqualTo(1);
            assertThat(dispatcher.positionOf("f6")).isEqualTo(2);
        });

        fal.release("f1"); // the freed fal slot goes to the earlier pinned job, not to the later attempt
        fal.awaitCalls(3);
        assertThat(fal.calls().getLast()).isEqualTo("anchor");
        assertThat(dispatcher.positionOf("f6")).isEqualTo(1);

        releaseAll();
        Dispatched d = done(anchor);
        assertThat(d.providerId()).isEqualTo("fal");
        assertThat(d.result()).isInstanceOf(GenerationResult.Success.class);
    }

    @Test
    void anAttemptQueuedBeforeAPinnedJobGetsTheFreedSlotFirst() {
        startDispatcher("fal", "bfl");
        attempts("f1", "f2", "f3", "f4", "f5", "f6");
        CompletableFuture<Dispatched> anchor = pinned("anchor", "fal", false);
        fal.awaitCalls(2);
        bfl.awaitCalls(3);

        fal.release("f1");
        fal.awaitCalls(3);
        assertThat(fal.calls().getLast()).isEqualTo("f6");

        bfl.release("f3"); // a free bfl slot is of no use to a job pinned to fal
        stays(() -> {
            assertThat(dispatcher.positionOf("anchor")).isEqualTo(1);
            assertThat(bfl.calls()).hasSize(3);
        });

        fal.release("f2");
        fal.awaitCalls(4);
        assertThat(fal.calls().getLast()).isEqualTo("anchor");
        releaseAll();
        assertThat(done(anchor).providerId()).isEqualTo("fal");
    }

    // ------------------------------------------------------------------ circuits

    @Test
    void aPinnedJobOnAnOpenCircuitFailsAtOnceWithoutCallingTheProvider() {
        startDispatcher("fal", "bfl");
        openCircuit("bfl");
        attempts("f1", "f2", "f3"); // fal is full and bfl is open: f3 waits ahead of the pinned job
        fal.awaitCalls(2);

        CompletableFuture<Dispatched> preview = pinned("preview", "bfl", false);

        assertThat(preview).succeedsWithin(Duration.ofMillis(300));
        Dispatched d = preview.join();
        assertThat(d.result()).isEqualTo(new GenerationResult.Failure(FailureKind.PROVIDER_ERROR, "circuit open"));
        assertThat(d.latencyMs()).isZero();
        assertThat(bfl.calls()).isEmpty();
        assertThat(startedOn).doesNotContainKey("preview");
        assertThat(dispatcher.positionOf("f3")).isEqualTo(1);
    }

    @Test
    void aProbeRunsOnAnOpenCircuitAndItsSuccessClosesIt() {
        startDispatcher("fal", "bfl");
        openCircuit("fal");
        fal.release("probe");

        Dispatched d = done(pinned("probe", "fal", true));

        assertThat(d.providerId()).isEqualTo("fal");
        assertThat(d.result()).isInstanceOf(GenerationResult.Success.class);
        assertThat(fal.calls()).containsExactly("probe");
        assertThat(registry.circuit("fal")).isEqualTo(CircuitState.CLOSED);
        assertThat(transitions).containsExactly("opened:fal", "closed:fal");
    }

    @Test
    void failedAttemptsOpenTheActiveProvidersCircuitAndLaterAttemptsOverflowToTheNextListedProvider() {
        startDispatcher("fal", "bfl");
        for (String id : List.of("e1", "e2", "e3")) {
            fal.respond(id, new GenerationResult.Failure(FailureKind.TIMEOUT, "slow"));
            fal.release(id);
            assertThat(done(attempt(id)).providerId()).isEqualTo("fal");
        }
        assertThat(registry.circuit("fal")).isEqualTo(CircuitState.OPEN);
        assertThat(transitions).containsExactly("opened:fal");

        // fal stays active (the domain switches it), but it takes nothing while open
        bfl.release("j1");
        assertThat(done(attempt("j1")).providerId()).isEqualTo("bfl");
        assertThat(fal.calls()).containsExactly("e1", "e2", "e3");
    }

    @Test
    void whileASplitProviderIsClosedAttemptsNeverFallBackToTheRestOfTheChain() {
        startDispatcher("fal", "bfl");
        openCircuit("fal"); // still active: the domain has not switched yet, and bfl is closed
        attempts("j1", "j2", "j3", "j4");

        bfl.awaitCalls(3);
        stays(() -> {
            assertThat(bfl.calls()).containsExactlyInAnyOrder("j1", "j2", "j3");
            assertThat(dispatcher.positionOf("j4")).isEqualTo(1);
            assertThat(fal.calls()).isEmpty();
            assertThat(other.calls()).isEmpty();
        });
    }

    @Test
    void attemptsFallBackToTheRestOfTheChainWhenNoSplitProviderIsClosed() {
        startDispatcher("fal", "bfl");
        openCircuit("fal");
        openCircuit("bfl");
        other.release("j1");

        Dispatched d = done(attempt("j1"));

        assertThat(d.providerId()).isEqualTo("other");
        assertThat(startedOn).containsEntry("j1", "other");
        assertThat(fal.calls()).isEmpty();
        assertThat(bfl.calls()).isEmpty();
    }

    @Test
    void aHalfOpenProviderTakesOneTrialAtATime() {
        startDispatcher("fal", "bfl");
        openCircuit("fal");
        clock.advanceSeconds(30);
        openCircuit("bfl");
        openCircuit("other");
        Map<String, CompletableFuture<Dispatched>> jobs = attempts("t1", "t2", "t3");
        stays(() -> assertThat(dispatcher.length()).as("every circuit is open").isEqualTo(3));

        // fal's open period ends (bfl and other stay open 30 s more); nobody wakes the dispatcher
        clock.advanceSeconds(30);
        fal.awaitCalls(1);
        assertThat(registry.circuit("fal")).isEqualTo(CircuitState.HALF_OPEN);
        stays(() -> {
            assertThat(fal.calls()).containsExactly("t1");
            assertThat(dispatcher.inFlight("fal")).as("fal has a free slot, but only the trial runs").isEqualTo(1);
            assertThat(dispatcher.positionOf("t2")).isEqualTo(1);
            assertThat(dispatcher.positionOf("t3")).isEqualTo(2);
        });

        fal.release("t1"); // the trial succeeds: the circuit closes and the waiting attempts follow
        fal.awaitCalls(3);
        assertThat(registry.circuit("fal")).isEqualTo(CircuitState.CLOSED);
        assertThat(fal.calls()).containsExactlyInAnyOrder("t1", "t2", "t3");
        assertThat(transitions).containsExactly("opened:fal", "opened:bfl", "opened:other", "closed:fal");
        releaseAll();
        assertThat(providersOf(jobs).values()).containsOnly("fal");
    }
}
