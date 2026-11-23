package io.github.ewcastroh.promptbattle.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;

import io.github.ewcastroh.promptbattle.game.MutableClock;
import io.github.ewcastroh.promptbattle.persistence.ImageStore;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SPEC §4.2 and §4.3 / PLAN A2 DeterminismCheckTest: the 1e-4 threshold, averaging two calls per image, the
 * {@code (image, provider)} cache, shared concurrent calls, and a prefetch that never waits or poisons scoring.
 */
class DeterminismCheckTest {

    @TempDir
    Path dir;
    ImageStore images;
    final RecordingEmbeddingProvider gemini = new RecordingEmbeddingProvider("gemini");
    final RecordingEmbeddingProvider cohere = new RecordingEmbeddingProvider("cohere");
    final MutableClock clock = new MutableClock(Instant.parse("2026-12-02T15:00:00Z"));
    String target;
    String entry;

    @BeforeEach
    void setUp() {
        images = new ImageStore(dir);
        target = RecordingEmbeddingProvider.image(images, "target");
        entry = RecordingEmbeddingProvider.image(images, "entry");
    }

    private EmbeddingService service(int maxConcurrency) {
        return new EmbeddingService(List.of(gemini, cohere), images, maxConcurrency, clock);
    }

    @Test
    void theThresholdIsOneTenThousandthAfterNormalization() {
        double[] v = { 0.6, 0.8, 0 };
        assertThat(DeterminismCheck.maxDiff(v, v.clone())).isZero();
        assertThat(DeterminismCheck.maxDiff(v, new double[] { 1.2, 1.6, 0 })).as("scale does not matter")
                .isCloseTo(0, within(1e-15));
        double big = DeterminismCheck.maxDiff(v, new double[] { 0.6, 0.8, 2e-4 });
        double small = DeterminismCheck.maxDiff(v, new double[] { 0.6, 0.8, 5e-5 });
        assertThat(DeterminismCheck.needsAveraging(big)).isTrue();
        assertThat(DeterminismCheck.needsAveraging(small)).isFalse();
        assertThat(DeterminismCheck.maxDiff(v, new double[] { 0.6, 0.8 })).isInfinite();
    }

    @Test
    void averagingIsTheNormalizedMeanOfTwoCalls() {
        gemini.jitter = 0.05;
        EmbeddingService service = service(8);

        double[] averaged = service.embed(entry, "gemini", true);

        double[] a = EmbeddingService.normalize(gemini.vectorFor("entry", 1));
        double[] b = EmbeddingService.normalize(gemini.vectorFor("entry", 2));
        double[] mean = new double[a.length];
        for (int i = 0; i < mean.length; i++) {
            mean[i] = (a[i] + b[i]) / 2;
        }
        assertThat(averaged).containsExactly(EmbeddingService.normalize(mean), within(1e-12));
        assertThat(gemini.calls("entry")).isEqualTo(2);
        assertThat(service.embed(entry, "gemini", false)).as("the first call is reused without averaging")
                .containsExactly(a, within(1e-12));
        assertThat(gemini.calls("entry")).isEqualTo(2);
    }

    @Test
    void theCacheIsPerImageAndProvider() {
        EmbeddingService service = service(8);

        service.embed(target, "gemini");
        service.embed(target, "gemini");
        service.embed(target, "cohere");

        assertThat(gemini.calls("target")).isEqualTo(1);
        assertThat(cohere.calls("target")).as("another provider is another vector").isEqualTo(1);
    }

    @Test
    void concurrentRequestsForTheSameImageShareOneCall() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        gemini.gate = gate;
        EmbeddingService service = service(8);

        CompletableFuture<double[]> first = CompletableFuture.supplyAsync(() -> service.embed(target, "gemini"));
        CompletableFuture<double[]> second = CompletableFuture.supplyAsync(() -> service.embed(target, "gemini"));
        await().atMost(Duration.ofSeconds(2)).until(() -> gemini.calls("target") == 1);
        Thread.sleep(50); // give the second request time to (wrongly) start its own call
        gate.countDown();

        assertThat(first.get()).containsExactly(second.get());
        assertThat(gemini.calls("target")).isEqualTo(1);
    }

    @Test
    void aPrefetchIsSkippedWithoutCapacityAndScoringStillEmbeds() {
        gemini.capacity = false;
        EmbeddingService service = service(8);

        service.prefetch(entry, "gemini");
        assertThat(gemini.calls("entry")).isZero();

        service.embed(entry, "gemini");
        assertThat(gemini.calls("entry")).isEqualTo(1);
    }

    @Test
    void aPrefetchNeverQueuesBehindScoringForACallSlot() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        gemini.gate = gate;
        EmbeddingService service = service(1);
        CompletableFuture<double[]> scoring = CompletableFuture.supplyAsync(() -> service.embed(target, "gemini"));
        await().atMost(Duration.ofSeconds(2)).until(() -> gemini.calls("target") == 1);

        long started = System.nanoTime();
        service.prefetch(entry, "gemini"); // the only slot is taken: gives up at once
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
        assertThat(gemini.calls("entry")).isZero();

        gate.countDown();
        scoring.get();
        gemini.gate = null;
        service.embed(entry, "gemini");
        assertThat(gemini.calls("entry")).as("nothing cached from the skipped prefetch").isEqualTo(1);
    }

    /** Code review I2: scoring that joins a failed prefetch (no retries) redoes the call instead of failing. */
    @Test
    void scoringJoiningAFailedPrefetchRedoesTheCall() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        gemini.gate = gate;
        gemini.failNext.put("entry", 1);
        EmbeddingService service = service(8);
        CompletableFuture<Void> prefetch = CompletableFuture.runAsync(() -> service.prefetch(entry, "gemini"));
        await().atMost(Duration.ofSeconds(2)).until(() -> gemini.calls("entry") == 1);
        CompletableFuture<double[]> scoring = CompletableFuture.supplyAsync(() -> service.embed(entry, "gemini"));
        Thread.sleep(50); // scoring joins the prefetch's call
        gate.countDown();

        assertThat(scoring.get()).hasSize(RecordingEmbeddingProvider.DIMENSIONS);
        prefetch.get();
        assertThat(gemini.calls("entry")).as("the failed prefetch, then scoring's own call").isEqualTo(2);
    }

    @Test
    void aSkippedPrefetchDoesNotPoisonTheCache() {
        gemini.skipWithoutWaiting = true;
        EmbeddingService service = service(8);

        service.prefetch(entry, "gemini");
        double[] v = service.embed(entry, "gemini");

        assertThat(v).hasSize(RecordingEmbeddingProvider.DIMENSIONS);
        assertThat(gemini.calls("entry")).isEqualTo(1);
        assertThat(service.health("gemini").lastFailureAt()).as("a skip is not a provider failure").isNull();
    }
}
