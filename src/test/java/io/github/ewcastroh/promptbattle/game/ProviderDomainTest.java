package io.github.ewcastroh.promptbattle.game;

import static io.github.ewcastroh.promptbattle.game.GameAssertions.assertCode;
import static io.github.ewcastroh.promptbattle.game.GameFixture.BOG;
import static io.github.ewcastroh.promptbattle.game.GameFixture.MDE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ewcastroh.promptbattle.game.Outcomes.Scored;
import io.github.ewcastroh.promptbattle.game.Outcomes.ScoringFailed;
import io.github.ewcastroh.promptbattle.persistence.SnapshotCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * SPEC §5.4, §4.2, §4.3, D-013 and D-023 in the domain: event-wide failover and forced switches, the embedding
 * failover alert, SCORING_FAILED and rescore, determinism averaging and the daily quota alert.
 */
class ProviderDomainTest {

    static GameSettings twoProviders(boolean eager) {
        return new GameSettings(20, 3, true, eager, GameFixture.ANCHOR_PROMPTS, List.of("fal", "bfl"),
                List.of("gemini", "cohere"), "https://ui.test/");
    }

    private final GameFixture fx = new GameFixture(twoProviders(false));
    private final Game game = fx.game;

    private List<Alert> alerts(String code) {
        return fx.state.alerts.stream().filter(a -> a.code.equals(code)).toList();
    }

    /** Medellín's round open, Bogotá's closing with a generation still in flight. */
    private String openInMdeClosingInBog() {
        String round = fx.scoredRound("R1");
        game.startRound(MDE, round);
        game.startRound(BOG, round);
        fx.attempt(fx.join(BOG, "Bea"), "still generating");
        game.closeRound(BOG, round);
        assertThat(fx.run(BOG, round).status).isEqualTo(RunStatus.CLOSING);
        fx.clearEffects();
        return round;
    }

    // ------------------------------------------------------------------ image providers

    @Test
    void theActiveProviderOpeningSwitchesEveryOpenAndClosingRunOfBothCities() {
        String round = openInMdeClosingInBog();
        fx.circuits.put("fal", CircuitState.OPEN);

        game.imageCircuitOpened("fal");

        assertThat(game.activeImageProvider()).isEqualTo("bfl");
        assertThat(fx.run(MDE, round).imageProvider).isEqualTo("bfl");
        assertThat(fx.run(BOG, round).imageProvider).as("closing runs follow too").isEqualTo("bfl");
        assertThat(alerts("CIRCUIT_OPEN")).singleElement()
                .satisfies(a -> assertThat(a.params).containsExactly(Map.entry("providerId", "fal")));
        assertThat(alerts("PROVIDER_SWITCHED")).singleElement().satisfies(a -> {
            assertThat(a.city).as("event-wide").isNull();
            assertThat(a.params).containsEntry("from", "fal").containsEntry("to", "bfl");
        });
        assertThat(fx.take(Effect.ActiveImageProvider.class))
                .containsExactly(new Effect.ActiveImageProvider("bfl", false));
    }

    @Test
    void withoutAClosedProviderTheActiveOneStays() {
        openInMdeClosingInBog();
        fx.circuits.put("fal", CircuitState.OPEN);
        fx.circuits.put("bfl", CircuitState.HALF_OPEN);

        game.imageCircuitOpened("fal");

        assertThat(game.activeImageProvider()).isEqualTo("fal");
        assertThat(alerts("CIRCUIT_OPEN")).hasSize(1);
        assertThat(alerts("PROVIDER_SWITCHED")).isEmpty();
        assertThat(fx.take(Effect.ActiveImageProvider.class)).isEmpty();
    }

    @Test
    void anotherProviderOpeningOnlyAlerts() {
        openInMdeClosingInBog();
        fx.circuits.put("bfl", CircuitState.OPEN);

        game.imageCircuitOpened("bfl");

        assertThat(game.activeImageProvider()).isEqualTo("fal");
        assertThat(alerts("CIRCUIT_OPEN")).hasSize(1);
        assertThat(alerts("PROVIDER_SWITCHED")).isEmpty();
    }

    @Test
    void aStaleOpenEventDoesNotUndoACircuitThatClosedAgain() {
        String round = openInMdeClosingInBog();
        // fal opened, then an admin forced it (its circuit closed) before the open event was processed
        game.imageCircuitOpened("fal");

        assertThat(game.activeImageProvider()).isEqualTo("fal");
        assertThat(fx.run(MDE, round).imageProvider).isEqualTo("fal");
        assertThat(alerts("PROVIDER_SWITCHED")).isEmpty();
    }

    @Test
    void aRecoveredProviderDoesNotTakeOverAgain() {
        openInMdeClosingInBog();
        fx.circuits.put("fal", CircuitState.OPEN);
        game.imageCircuitOpened("fal");
        fx.circuits.put("fal", CircuitState.CLOSED);

        game.imageCircuitClosed("fal");

        assertThat(game.activeImageProvider()).as("no automatic fail-back (D-023)").isEqualTo("bfl");
        assertThat(alerts("CIRCUIT_CLOSED")).hasSize(1);
        assertThat(alerts("PROVIDER_SWITCHED")).hasSize(1);
    }

    @Test
    void aClosingCircuitReplacesAnUnhealthyActiveProvider() {
        String round = openInMdeClosingInBog();
        fx.circuits.put("fal", CircuitState.OPEN);
        fx.circuits.put("bfl", CircuitState.OPEN);
        game.imageCircuitOpened("fal");
        fx.circuits.put("bfl", CircuitState.CLOSED);

        game.imageCircuitClosed("bfl");

        assertThat(game.activeImageProvider()).isEqualTo("bfl");
        assertThat(fx.run(MDE, round).imageProvider).isEqualTo("bfl");
        assertThat(alerts("PROVIDER_SWITCHED")).singleElement()
                .satisfies(a -> assertThat(a.params).containsEntry("from", "fal").containsEntry("to", "bfl"));
    }

    @Test
    void eitherAdminCanForceAProviderAndItsCircuitCloses() {
        String round = openInMdeClosingInBog();

        game.forceImageProvider("bfl", BOG);

        assertThat(game.activeImageProvider()).isEqualTo("bfl");
        assertThat(fx.run(MDE, round).imageProvider).as("the other city's open run follows").isEqualTo("bfl");
        assertThat(alerts("PROVIDER_FORCED")).singleElement().satisfies(a -> {
            assertThat(a.city).isNull();
            assertThat(a.params).containsEntry("providerId", "bfl").containsEntry("byCity", BOG);
        });
        assertThat(fx.take(Effect.ActiveImageProvider.class))
                .containsExactly(new Effect.ActiveImageProvider("bfl", true));
    }

    @Test
    void onlyAConfiguredChainProviderCanBeForced() {
        for (String unknown : new String[] { "runware", "fake" }) {
            assertThatThrownBy(() -> game.forceImageProvider(unknown, MDE)).isInstanceOfSatisfying(GameException.class,
                    e -> {
                        assertThat(e.code()).isEqualTo(ErrorCode.PROVIDER_NOT_FOUND);
                        assertThat(e.params()).containsEntry("providerId", unknown);
                    });
        }
        assertThat(game.activeImageProvider()).isEqualTo("fal");
    }

    @Test
    void aRoundCannotStartWhenEveryCircuitIsOpen() {
        String round = fx.scoredRound("R1");
        fx.circuits.put("fal", CircuitState.OPEN);
        fx.circuits.put("bfl", CircuitState.OPEN);
        assertCode(ErrorCode.PROVIDER_UNAVAILABLE, () -> game.startRound(MDE, round));
        assertThat(game.pinnedImageProvider()).isNull();

        fx.circuits.put("bfl", CircuitState.HALF_OPEN);
        assertThat(game.pinnedImageProvider()).as("a half-open provider may take a trial").isEqualTo("bfl");
        game.startRound(MDE, round);
        assertThat(fx.phase(MDE)).isEqualTo(Phase.ROUND_OPEN);
    }

    @Test
    void calibrationFailsAtOnceWhenEveryCircuitIsOpen() {
        fx.circuits.put("fal", CircuitState.OPEN);
        fx.circuits.put("bfl", CircuitState.OPEN);
        Views.RoundDefinitionView v = game.createRound(new RoundInput("R", "h", 480, 3, true, 5L),
                new TargetChoice.Uploaded("t"));

        assertThat(v.calibration().status()).isEqualTo(CalibrationStatus.FAILED);
        assertThat(alerts("CALIBRATION_FAILED")).hasSize(1);
        assertThat(fx.take(Effect.Calibrate.class)).isEmpty();
    }

    @Test
    void startingAScoredRoundPrefetchesTheTargetAndEveryAnchor() {
        String round = fx.scoredRound("R1");
        RoundDefinition d = game.round(round);

        game.startRound(MDE, round);

        List<String> expected = new ArrayList<>();
        expected.add(d.targetImageId);
        d.calibration.anchors.forEach(a -> expected.add(a.imageId));
        assertThat(fx.take(Effect.PrefetchEmbedding.class))
                .allSatisfy(p -> assertThat(p.provider()).isEqualTo("gemini"))
                .extracting(Effect.PrefetchEmbedding::imageId).containsExactlyElementsOf(expected);
    }

    // ------------------------------------------------------------------ embeddings

    private String scoredRunAwaitingScores() {
        String round = fx.scoredRound("R1");
        game.startRound(MDE, round);
        fx.done(fx.join(MDE, "Ana"), "a lighthouse");
        game.closeRound(MDE, round);
        return round;
    }

    private static Scored scored(String provider, Effect.ScoreRun run) {
        List<Double> anchors = new ArrayList<>();
        run.anchorImageIds().forEach(a -> anchors.add(0.1));
        Map<String, Double> cos = new java.util.LinkedHashMap<>();
        run.entryImageIds().keySet().forEach(id -> cos.put(id, 0.7));
        return new Scored(provider, anchors, cos);
    }

    @Test
    void scoringWithTheNextProviderRaisesEmbeddingFailoverForThatCity() {
        String round = scoredRunAwaitingScores();
        Effect.ScoreRun run = fx.take(Effect.ScoreRun.class).getFirst();
        assertThat(run.embeddingProvider()).isEqualTo("gemini");

        game.scoringFinished(MDE, round, scored("cohere", run));

        assertThat(fx.run(MDE, round).embeddingProvider).isEqualTo("cohere");
        assertThat(alerts("EMBEDDING_FAILOVER")).singleElement().satisfies(a -> {
            assertThat(a.city).isEqualTo(MDE);
            assertThat(a.params).containsEntry("from", "gemini").containsEntry("to", "cohere")
                    .containsEntry("roundId", round);
        });
    }

    @Test
    void scoringWithTheRunsOwnProviderRaisesNoFailoverAlert() {
        String round = scoredRunAwaitingScores();
        game.scoringFinished(MDE, round, scored("gemini", fx.take(Effect.ScoreRun.class).getFirst()));
        assertThat(alerts("EMBEDDING_FAILOVER")).isEmpty();
    }

    @Test
    void failedScoringWaitsForARescoreWithTheSameProviderFirst() {
        String round = scoredRunAwaitingScores();
        fx.take(Effect.ScoreRun.class);

        game.scoringFinished(MDE, round, new ScoringFailed("every provider failed"));

        assertThat(fx.run(MDE, round).status).isEqualTo(RunStatus.SCORING_FAILED);
        assertThat(fx.phase(MDE)).isEqualTo(Phase.ROUND_CLOSING);
        assertThat(alerts("SCORING_FAILED")).singleElement().satisfies(a -> {
            assertThat(a.city).isEqualTo(MDE);
            assertThat(a.params).containsEntry("roundId", round);
        });

        game.rescore(MDE, round);

        assertThat(fx.run(MDE, round).status).isEqualTo(RunStatus.SCORING);
        assertThat(fx.take(Effect.ScoreRun.class)).singleElement()
                .satisfies(s -> assertThat(s.embeddingProvider()).isEqualTo("gemini"));
    }

    @Test
    void averagingFollowsTheLatestProbeAndAlertsWhenItTurnsOn() {
        game.determinismChecked("gemini", 2e-4, true);
        game.determinismChecked("gemini", 3e-4, true);

        EmbeddingProviderState s = fx.state.embeddingProviders.get("gemini");
        assertThat(s.averaging).isTrue();
        assertThat(s.maxDiff).isEqualTo(3e-4);
        assertThat(s.checkedAt).isEqualTo(fx.clock.instant());
        assertThat(alerts("DETERMINISM_AVERAGING")).as("only when it turns on").singleElement()
                .satisfies(a -> assertThat(a.params).containsEntry("providerId", "gemini").containsEntry("maxDiff", 2e-4));

        game.determinismChecked("gemini", 0.0, false);

        assertThat(s.averaging).isFalse();
        assertThat(alerts("DETERMINISM_AVERAGING")).hasSize(1);
        assertThat(fx.take(Effect.EmbeddingAveraging.class)).containsExactly(
                new Effect.EmbeddingAveraging("gemini", true), new Effect.EmbeddingAveraging("gemini", true),
                new Effect.EmbeddingAveraging("gemini", false));
    }

    @Test
    void theQuotaAlertFiresOnceADayAtEightyPercent() {
        game.embeddingUsage("gemini", "2026-12-02", 799, 1000);
        assertThat(alerts("EMBEDDING_QUOTA_LOW")).isEmpty();

        game.embeddingUsage("gemini", "2026-12-02", 800, 1000);
        game.embeddingUsage("gemini", "2026-12-02", 900, 1000);
        game.embeddingUsage("gemini", "2026-12-02", 850, 1000); // a late report from a parallel call

        assertThat(alerts("EMBEDDING_QUOTA_LOW")).singleElement().satisfies(a -> {
            assertThat(a.city).isNull();
            assertThat(a.params).containsEntry("used", 800).containsEntry("limit", 1000);
        });
        assertThat(fx.state.embeddingProviders.get("gemini").usedToday).isEqualTo(900);

        game.embeddingUsage("gemini", "2026-12-03", 800, 1000);
        assertThat(alerts("EMBEDDING_QUOTA_LOW")).as("a new quota day").hasSize(2);
        assertThat(fx.state.embeddingProviders.get("gemini").usedToday).isEqualTo(800);
    }

    @Test
    void theEventResetKeepsTheCountButLetsTheQuotaAlertFireAgain() {
        game.embeddingUsage("gemini", "2026-12-02", 820, 1000);
        game.resetEvent("RESET");
        assertThat(fx.state.alerts).isEmpty();

        game.embeddingUsage("gemini", "2026-12-02", 821, 1000);

        assertThat(fx.state.embeddingProviders.get("gemini").usedToday).isEqualTo(821);
        assertThat(alerts("EMBEDDING_QUOTA_LOW")).hasSize(1);
    }

    @Test
    void providerStateSurvivesTheSnapshot() {
        game.forceImageProvider("bfl", MDE);
        game.determinismChecked("gemini", 2e-4, true);
        game.embeddingUsage("gemini", "2026-12-02", 120, 1000);
        SnapshotCodec codec = new SnapshotCodec();

        EventState restored = codec.decode(codec.encode(fx.state));
        Game again = new Game(restored, twoProviders(false), fx.clock, Moderation.NONE,
                (k, s) -> new Commentary("es", "en"), QueuePositions.NONE, ProviderHealth.ALL_CLOSED,
                new SplittableRandom(1));

        Views.ProviderState view = again.views().providerState();
        assertThat(view.activeImageProvider()).isEqualTo("bfl");
        assertThat(view.embedding().get("gemini")).satisfies(g -> {
            assertThat(g.averaging()).isTrue();
            assertThat(g.maxDiff()).isEqualTo(2e-4);
            assertThat(g.usageDay()).isEqualTo("2026-12-02");
            assertThat(g.usedToday()).isEqualTo(120);
        });
        again.publishProviderState();
        assertThat(again.drainEffects()).containsExactly(new Effect.ActiveImageProvider("bfl", false),
                new Effect.EmbeddingAveraging("gemini", true));
    }
}
