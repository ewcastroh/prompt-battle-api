package io.github.ewcastroh.promptbattle.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.ewcastroh.promptbattle.game.FailureKind;
import io.github.ewcastroh.promptbattle.game.MutableClock;
import io.github.ewcastroh.promptbattle.generation.GenerationDispatcher.Dispatched;
import io.github.ewcastroh.promptbattle.generation.GenerationDispatcher.Target;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.awaitility.core.ThrowingRunnable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** SPEC §5.5, PLAN A2 QueueFairnessTest: one FIFO for both cities, the per-provider limit, queue positions, cancel. */
class QueueFairnessTest {

    private static final Duration TIMEOUT = Duration.ofMillis(800);
    /** Longer than one periodic re-scan of the dispatcher: a job that must wait had every chance to start. */
    private static final Duration QUIET = Duration.ofMillis(GenerationDispatcher.RECHECK_MILLIS + 50);
    private static final GenerationResult.Failure CANCELLED =
            new GenerationResult.Failure(FailureKind.PROVIDER_ERROR, "cancelled");

    private final GatedImageGenerator fal = new GatedImageGenerator("fal", 2);
    private final GenerationDispatcher dispatcher = new GenerationDispatcher(new ProviderRegistry(List.of(fal),
            List.of("fal"), List.of(), new MutableClock(Instant.parse("2026-12-02T15:00:00Z"))));

    @AfterEach
    void close() {
        fal.releaseAll();
        dispatcher.close();
    }

    private CompletableFuture<Dispatched> submit(String jobId) {
        return dispatcher.submit(jobId, Target.FLEXIBLE, GenerationRequest.square(jobId, 42), null);
    }

    private Map<String, CompletableFuture<Dispatched>> submitAll(String... jobIds) {
        Map<String, CompletableFuture<Dispatched>> jobs = new LinkedHashMap<>();
        for (String id : jobIds) {
            jobs.put(id, submit(id));
        }
        return jobs;
    }

    private List<Integer> positions(String... jobIds) {
        return Arrays.stream(jobIds).map(dispatcher::positionOf).toList();
    }

    private static Dispatched done(CompletableFuture<Dispatched> job) {
        assertThat(job).succeedsWithin(TIMEOUT);
        return job.join();
    }

    private static void stays(ThrowingRunnable assertion) {
        await().during(QUIET).atMost(QUIET.plus(TIMEOUT)).pollInterval(Duration.ofMillis(10)).untilAsserted(assertion);
    }

    @Test
    void attemptsOfBothCitiesStartInSubmitOrder() {
        List<String> ids = List.of("mde-1", "bog-1", "mde-2", "bog-2", "mde-3", "bog-3", "mde-4", "bog-4");
        Map<String, CompletableFuture<Dispatched>> jobs = submitAll(ids.toArray(String[]::new));

        fal.awaitCalls(2);
        assertThat(fal.calls()).containsExactlyInAnyOrder("mde-1", "bog-1");
        // Free one slot at a time (always the job that started last), so exactly one job can start each time.
        for (int started = 2; started < ids.size(); started++) {
            fal.release(fal.calls().getLast());
            fal.awaitCalls(started + 1);
        }

        assertThat(fal.calls().subList(2, ids.size())).containsExactlyElementsOf(ids.subList(2, ids.size()));
        fal.releaseAll();
        jobs.values().forEach(job -> {
            Dispatched d = done(job);
            assertThat(d.providerId()).isEqualTo("fal");
            assertThat(d.result()).isInstanceOf(GenerationResult.Success.class);
        });
        assertThat(fal.maxInFlight()).isEqualTo(2);
    }

    @Test
    void theProviderNeverRunsMoreCallsThanItsLimitEvenUnderABurst() {
        List<CompletableFuture<Dispatched>> jobs = new CopyOnWriteArrayList<>();
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService cities = Executors.newFixedThreadPool(2)) {
            for (String city : List.of("mde", "bog")) {
                cities.submit(() -> {
                    go.await();
                    for (int i = 1; i <= 25; i++) {
                        jobs.add(submit(city + "-" + i));
                    }
                    return null;
                });
            }
            go.countDown();
        }
        assertThat(jobs).hasSize(50);

        fal.awaitCalls(2);
        stays(() -> {
            assertThat(fal.calls()).hasSize(2);
            assertThat(dispatcher.length()).isEqualTo(48);
        });

        fal.releaseAll(Duration.ofMillis(2)); // the other 48 follow, each holding its slot for a moment
        for (CompletableFuture<Dispatched> job : jobs) {
            assertThat(job).succeedsWithin(Duration.ofSeconds(2)).extracting(Dispatched::providerId).isEqualTo("fal");
        }
        assertThat(fal.calls()).hasSize(50).doesNotHaveDuplicates();
        assertThat(fal.maxInFlight()).isEqualTo(2);
        assertThat(dispatcher.peakInFlight("fal")).isEqualTo(2);
        assertThat(dispatcher.inFlight("fal")).isZero();
        assertThat(dispatcher.length()).isZero();
    }

    @Test
    void queuePositionsCountDownAsJobsStartAndAreNullOnceStarted() {
        String[] ids = {"mde-1", "bog-1", "mde-2", "bog-2", "mde-3", "bog-3"};
        Map<String, CompletableFuture<Dispatched>> jobs = submitAll(ids);

        fal.awaitCalls(2);
        assertThat(positions(ids)).containsExactly(null, null, 1, 2, 3, 4);
        assertThat(dispatcher.length()).isEqualTo(4);
        assertThat(dispatcher.started("mde-1")).as("no start callback: not a player attempt").isFalse();

        fal.release("mde-1");
        fal.awaitCalls(3);
        assertThat(positions(ids)).containsExactly(null, null, null, 1, 2, 3);

        fal.release("bog-1");
        fal.awaitCalls(4);
        assertThat(positions(ids)).containsExactly(null, null, null, null, 1, 2);
        assertThat(dispatcher.length()).isEqualTo(2);

        fal.releaseAll();
        jobs.values().forEach(QueueFairnessTest::done);
        assertThat(positions(ids)).containsOnlyNulls();
        assertThat(dispatcher.positionOf("unknown")).isNull();
        assertThat(dispatcher.length()).isZero();
    }

    /** API.md §5: an attempt out of the FIFO counts as started until the domain applied its result. */
    @Test
    void anAttemptStaysStartedUntilTheDomainForgetsIt() {
        CompletableFuture<Dispatched> running = dispatcher.submit("mde-1", Target.FLEXIBLE,
                GenerationRequest.square("mde-1", 42), provider -> {
                });
        fal.awaitCalls(1);
        assertThat(dispatcher.positionOf("mde-1")).isNull();
        assertThat(dispatcher.started("mde-1")).isTrue();

        fal.release("mde-1");
        done(running);
        assertThat(dispatcher.started("mde-1")).as("result not applied by the domain yet").isTrue();
        Dispatched again = done(dispatcher.submit("mde-1", Target.FLEXIBLE, GenerationRequest.square("x", 1), null));
        assertThat(again.providerId()).as("an id still running or unapplied is rejected").isNull();

        dispatcher.forget("mde-1");
        assertThat(dispatcher.started("mde-1")).isFalse();
    }

    @Test
    void cancellingAPendingJobCompletesItWithoutCallingTheProvider() {
        Map<String, CompletableFuture<Dispatched>> jobs = submitAll("mde-1", "bog-1", "mde-2", "bog-2");
        fal.awaitCalls(2);

        assertThat(dispatcher.cancel("mde-2")).isTrue();

        Dispatched cancelled = done(jobs.get("mde-2"));
        assertThat(cancelled.providerId()).isNull();
        assertThat(cancelled.result()).isEqualTo(CANCELLED);
        assertThat(cancelled.latencyMs()).isZero();
        assertThat(dispatcher.positionOf("mde-2")).isNull();
        assertThat(dispatcher.positionOf("bog-2")).isEqualTo(1);
        assertThat(dispatcher.cancel("mde-2")).isFalse();
        assertThat(dispatcher.cancel("unknown")).isFalse();
        assertThat(dispatcher.cancel("mde-1")).as("a running job is not cancelled").isFalse();

        fal.releaseAll();
        for (String id : List.of("mde-1", "bog-1", "bog-2")) {
            Dispatched d = done(jobs.get(id));
            assertThat(d.providerId()).isEqualTo("fal");
            assertThat(d.result()).isInstanceOf(GenerationResult.Success.class);
        }
        assertThat(fal.calls()).containsExactlyInAnyOrder("mde-1", "bog-1", "bog-2");
    }

    @Test
    void cancelAllEmptiesTheQueueWhileRunningJobsFinish() {
        Map<String, CompletableFuture<Dispatched>> jobs = submitAll("mde-1", "bog-1", "mde-2", "bog-2", "mde-3");
        fal.awaitCalls(2);

        dispatcher.cancelAll();

        assertThat(dispatcher.length()).isZero();
        for (String id : List.of("mde-2", "bog-2", "mde-3")) {
            Dispatched d = done(jobs.get(id));
            assertThat(d.providerId()).isNull();
            assertThat(d.result()).isEqualTo(CANCELLED);
        }
        assertThat(jobs.get("mde-1")).isNotDone();
        assertThat(jobs.get("bog-1")).isNotDone();
        assertThat(dispatcher.inFlight("fal")).isEqualTo(2);

        fal.releaseAll();
        assertThat(done(jobs.get("mde-1")).result()).isInstanceOf(GenerationResult.Success.class);
        assertThat(done(jobs.get("bog-1")).result()).isInstanceOf(GenerationResult.Success.class);
        // the queue keeps working afterwards (an event reset cancels everything, then a new event starts)
        assertThat(done(submit("mde-4")).providerId()).isEqualTo("fal");
        assertThat(fal.calls()).containsExactlyInAnyOrder("mde-1", "bog-1", "mde-4");
    }
}
