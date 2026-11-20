package io.github.ewcastroh.promptbattle.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.junit.jupiter.api.Test;

/** SPEC §4.1: cosine after L2 normalization, calibration floor, clamped and rounded score. */
class ScoreCalculatorTest {

    @Test
    void cosineNormalizesVectors() {
        assertThat(ScoreCalculator.cosine(new double[] { 3, 0 }, new double[] { 10, 0 })).isEqualTo(1.0);
        assertThat(ScoreCalculator.cosine(new double[] { 1, 0 }, new double[] { 0, 5 })).isEqualTo(0.0);
        assertThat(ScoreCalculator.cosine(new double[] { 1, 1 }, new double[] { 2, 0 }))
                .isCloseTo(Math.sqrt(0.5), within(1e-12));
        assertThat(ScoreCalculator.cosine(new double[] { 0, 0 }, new double[] { 1, 0 })).isEqualTo(0.0);
    }

    @Test
    void floorIsTheMeanOfAnchorsAndExcludesAnchorsAboveZeroPointNine() {
        assertThat(ScoreCalculator.floor(List.of(0.2, 0.4, 0.6))).isCloseTo(0.4, within(1e-12));
        assertThat(ScoreCalculator.floor(List.of(0.2, 0.4, 0.95))).isCloseTo(0.3, within(1e-12));
        assertThat(ScoreCalculator.floor(List.of(0.2, 0.9))).as("0.9 itself is kept").isCloseTo(0.55, within(1e-12));
        assertThat(ScoreCalculator.floor(List.of(0.99))).isZero();
        assertThat(ScoreCalculator.floor(List.of())).isZero();
    }

    @Test
    void scoreIsNormalizedClampedAndRoundedToOneDecimal() {
        assertThat(ScoreCalculator.score(0.83, 0.0)).isEqualTo(83.0);
        assertThat(ScoreCalculator.score(0.7, 0.4)).isEqualTo(50.0);
        assertThat(ScoreCalculator.score(0.3, 0.4)).as("below the floor").isEqualTo(0.0);
        assertThat(ScoreCalculator.score(1.0, 0.4)).isEqualTo(100.0);
        assertThat(ScoreCalculator.score(1.2, 0.4)).as("clamped").isEqualTo(100.0);
        assertThat(ScoreCalculator.score(0.61234, 0.41)).isEqualTo(34.3); // 34.29…
        assertThat(ScoreCalculator.score(0.50025, 0.0)).isEqualTo(50.0); // 50.025 → 50.0
        assertThat(ScoreCalculator.score(0.5006, 0.0)).isEqualTo(50.1); // 50.06 → 50.1
        assertThat(ScoreCalculator.score(0.5, 1.0)).as("degenerate floor").isEqualTo(0.0);
    }

    @Test
    void scoringUsesTheRunFloorFromItsAnchors() {
        GameFixture fx = new GameFixture();
        String round = fx.scoredRound("R1");
        String token = fx.join("mde", "Ana");
        fx.game.startRound("mde", round);
        String id = fx.done(token, "x");
        fx.game.closeRound("mde", round);
        Effect.ScoreRun effect = fx.take(Effect.ScoreRun.class).getFirst();
        assertThat(effect.anchorImageIds()).hasSize(6);
        fx.game.scoringFinished("mde", round, new Outcomes.Scored("fake",
                List.of(0.3, 0.5, 0.95, 0.4, 0.2, 0.6), java.util.Map.of(id, 0.8)));
        RoundRun run = fx.run("mde", round);
        assertThat(run.floor).isCloseTo(0.4, within(1e-12));
        assertThat(run.entries.values().iterator().next().score).isEqualTo(66.7);
        assertThat(fx.game.views().roundDefinition(round).calibration().anchors())
                .extracting(Views.AnchorView::excluded).containsExactly(false, false, true, false, false, false);
        assertThat(fx.game.views().entries("mde", round).getFirst().cos()).isEqualTo(0.8);
    }
}
