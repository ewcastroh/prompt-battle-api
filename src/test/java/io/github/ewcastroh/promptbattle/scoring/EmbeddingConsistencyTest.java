package io.github.ewcastroh.promptbattle.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import io.github.ewcastroh.promptbattle.game.Effect;
import io.github.ewcastroh.promptbattle.game.MutableClock;
import io.github.ewcastroh.promptbattle.game.Outcomes;
import io.github.ewcastroh.promptbattle.game.ScoreCalculator;
import io.github.ewcastroh.promptbattle.persistence.ImageStore;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SPEC §4.3 / PLAN A2 EmbeddingConsistencyTest: every vector of a city run comes from one provider; a failure
 * re-embeds the whole run with the next provider; one city's failed try never fails the other city's scoring.
 */
class EmbeddingConsistencyTest {

    static final List<String> ANCHORS = List.of("a1", "a2", "a3");

    @TempDir
    Path dir;
    ImageStore images;
    final Map<String, String> ids = new LinkedHashMap<>();
    final RecordingEmbeddingProvider gemini = new RecordingEmbeddingProvider("gemini");
    final RecordingEmbeddingProvider cohere = new RecordingEmbeddingProvider("cohere");
    final MutableClock clock = new MutableClock(Instant.parse("2026-12-02T15:00:00Z"));

    @BeforeEach
    void storeImages() {
        images = new ImageStore(dir);
        for (String name : List.of("target", "a1", "a2", "a3", "e1", "e2", "e3", "e4")) {
            ids.put(name, RecordingEmbeddingProvider.image(images, name));
        }
    }

    private Effect.ScoreRun run(String city, String provider, String... entries) {
        Map<String, String> entryImages = new LinkedHashMap<>();
        for (String e : entries) {
            entryImages.put("attempt-" + e, ids.get(e));
        }
        return new Effect.ScoreRun(city, "round-1", provider, ids.get("target"),
                ANCHORS.stream().map(ids::get).toList(), entryImages);
    }

    private RunScorer scorer(List<RecordingEmbeddingProvider> providers, List<String> chain) {
        EmbeddingService service = new EmbeddingService(List.copyOf(providers), images, 8, clock);
        return new RunScorer(service, chain);
    }

    private static double cos(RecordingEmbeddingProvider p, String a, String b) {
        return ScoreCalculator.cosine(p.vectorFor(a, 1), p.vectorFor(b, 1));
    }

    @Test
    void aFailureReembedsTheWholeRunWithTheNextProviderAndNeverMixes() {
        gemini.failing.add("e2");

        Outcomes.Scored scored = scorer(List.of(gemini, cohere), List.of("gemini", "cohere"))
                .score(run("mde", "gemini", "e1", "e2", "e3"));

        assertThat(scored.embeddingProvider()).isEqualTo("cohere");
        for (String name : List.of("target", "a1", "a2", "a3", "e1", "e2", "e3")) {
            assertThat(cohere.calls(name)).as("cohere embedded %s", name).isEqualTo(1);
        }
        // every cosine comes from cohere vectors only
        for (int i = 0; i < ANCHORS.size(); i++) {
            assertThat(scored.anchorCos().get(i)).isCloseTo(cos(cohere, "target", ANCHORS.get(i)), within(1e-12));
        }
        for (String e : List.of("e1", "e2", "e3")) {
            assertThat(scored.entryCos().get("attempt-" + e)).isCloseTo(cos(cohere, "target", e), within(1e-12));
            assertThat(scored.entryCos().get("attempt-" + e)).as("not a gemini vector")
                    .isNotCloseTo(cos(gemini, "target", e), within(1e-6));
        }
        assertThat(gemini.calls("e2")).as("two tries with the run's provider").isEqualTo(2);
        assertThat(gemini.calls("target")).as("the second try reuses what the first one embedded").isEqualTo(1);
    }

    @Test
    void aFailedTryNeitherInterruptsNorRepeatsCallsStillInFlight() {
        gemini.failNext.put("e2", 1); // a transient error: the second try succeeds
        gemini.slow.put("e3", 300L); // still in flight when e2 fails

        Outcomes.Scored scored = scorer(List.of(gemini, cohere), List.of("gemini", "cohere"))
                .score(run("mde", "gemini", "e2", "e3"));

        assertThat(scored.embeddingProvider()).as("the second try reuses the call in flight").isEqualTo("gemini");
        assertThat(gemini.calls("e3")).as("not interrupted and sent again").isEqualTo(1);
        assertThat(gemini.calls("e2")).isEqualTo(2);
        assertThat(cohere.calls).isEmpty();
    }

    @Test
    void aUsedUpDailyQuotaSkipsTheSecondTry() {
        gemini.failing.add("e1");
        gemini.failKind = EmbeddingException.Kind.QUOTA_EXHAUSTED;

        Outcomes.Scored scored = scorer(List.of(gemini, cohere), List.of("gemini", "cohere"))
                .score(run("mde", "gemini", "e1"));

        assertThat(scored.embeddingProvider()).isEqualTo("cohere");
        assertThat(gemini.calls("e1")).isEqualTo(1);
    }

    @Test
    void withoutAnotherProviderScoringFails() {
        gemini.failing.add("e1");
        RunScorer scorer = scorer(List.of(gemini), List.of("gemini"));

        assertThatThrownBy(() -> scorer.score(run("mde", "gemini", "e1"))).isInstanceOf(EmbeddingException.class);
    }

    @Test
    void theRunsProviderIsTriedFirstEvenWhenItIsNotFirstInTheChain() {
        Outcomes.Scored scored = scorer(List.of(gemini, cohere), List.of("gemini", "cohere"))
                .score(run("mde", "cohere", "e1"));

        assertThat(scored.embeddingProvider()).isEqualTo("cohere");
        assertThat(gemini.calls).isEmpty();
    }

    @Test
    void oneCitysFailedTryNeverFailsTheOtherCitysScoringOfTheSharedTarget() throws Exception {
        gemini.failing.add("e2");
        gemini.delayMillis = 30; // both cities' scorings overlap on the shared target and anchors
        RunScorer scorer = scorer(List.of(gemini, cohere), List.of("gemini", "cohere"));

        CompletableFuture<Outcomes.Scored> mde = CompletableFuture.supplyAsync(
                () -> scorer.score(run("mde", "gemini", "e1", "e2")));
        CompletableFuture<Outcomes.Scored> bog = CompletableFuture.supplyAsync(
                () -> scorer.score(run("bog", "gemini", "e3", "e4")));

        assertThat(mde.get().embeddingProvider()).isEqualTo("cohere");
        assertThat(bog.get().embeddingProvider()).as("Bogotá is not affected by Medellín's failure")
                .isEqualTo("gemini");
        for (String shared : List.of("target", "a1", "a2", "a3")) {
            assertThat(gemini.calls(shared)).as("%s embedded once and shared", shared).isEqualTo(1);
        }
    }

    @Test
    void averagingUsesTwoCallsPerImageForTheWholeRun() {
        gemini.jitter = 0.01;
        EmbeddingService service = new EmbeddingService(List.of(gemini), images, 8, clock);
        service.setAveraging("gemini", true);

        Outcomes.Scored scored = new RunScorer(service, List.of("gemini")).score(run("mde", "gemini", "e1"));

        for (String name : List.of("target", "a1", "a2", "a3", "e1")) {
            assertThat(gemini.calls(name)).as(name).isEqualTo(2);
        }
        double[] target = mean(gemini, "target");
        assertThat(scored.entryCos().get("attempt-e1"))
                .isCloseTo(ScoreCalculator.cosine(target, mean(gemini, "e1")), within(1e-12));
    }

    private static double[] mean(RecordingEmbeddingProvider p, String name) {
        double[] a = EmbeddingService.normalize(p.vectorFor(name, 1));
        double[] b = EmbeddingService.normalize(p.vectorFor(name, 2));
        double[] m = new double[a.length];
        for (int i = 0; i < m.length; i++) {
            m[i] = (a[i] + b[i]) / 2;
        }
        return EmbeddingService.normalize(m);
    }
}
