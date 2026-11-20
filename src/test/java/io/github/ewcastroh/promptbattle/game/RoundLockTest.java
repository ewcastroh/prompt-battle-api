package io.github.ewcastroh.promptbattle.game;

import static io.github.ewcastroh.promptbattle.game.GameAssertions.assertCode;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** SPEC §2: a round definition can be edited until any city starts it; then it is locked. */
class RoundLockTest {

    private final GameFixture fx = new GameFixture();
    private final Game game = fx.game;

    @Test
    void editDeleteAndCalibrateAreLockedOnceAnyCityStarts() {
        String round = fx.scoredRound("R1");
        Views.RoundDefinitionView edited = game.updateRound(round,
                new RoundInput("Renamed", null, 300, null, null, null), null);
        assertThat(edited.name()).isEqualTo("Renamed");
        assertThat(edited.durationSec()).isEqualTo(300);
        assertThat(edited.calibration().status()).as("name change keeps calibration").isEqualTo(CalibrationStatus.READY);
        assertThat(edited.locked()).isFalse();

        game.startRound("bog", round);
        assertThat(game.views().roundDefinition(round).locked()).isTrue();
        RoundInput input = new RoundInput("Again", null, null, null, null, null);
        assertCode(ErrorCode.ROUND_LOCKED, () -> game.updateRound(round, input, null));
        assertCode(ErrorCode.ROUND_LOCKED, () -> game.deleteRound(round));
        assertCode(ErrorCode.ROUND_LOCKED, () -> game.recalibrate(round));
        assertThat(game.views().roundDefinition(round).name()).isEqualTo("Renamed");

        game.startRound("mde", round);
        assertThat(game.views().roundDefinition(round).runs()).extracting(Views.RunView::status)
                .containsExactly(RunStatus.OPEN, RunStatus.OPEN);
    }

    @Test
    void seedOrTargetChangeRestartsCalibrationAndStaleResultsAreIgnored() {
        String round = fx.scoredRound("R1");
        game.updateRound(round, new RoundInput(null, null, null, null, null, 99L), null);
        assertThat(game.views().roundDefinition(round).calibration().status()).isEqualTo(CalibrationStatus.PENDING);
        Effect.Calibrate first = fx.take(Effect.Calibrate.class).getFirst();
        assertThat(first.seed()).isEqualTo(99L);

        game.updateRound(round, new RoundInput(null, null, null, null, null, null),
                new TargetChoice.Uploaded("new-target"));
        Effect.Calibrate second = fx.take(Effect.Calibrate.class).getFirst();
        game.calibrationFinished(round, first.version(),
                new Outcomes.Calibrated("fake", java.util.List.of()));
        assertThat(game.views().roundDefinition(round).calibration().status())
                .as("stale version ignored").isEqualTo(CalibrationStatus.PENDING);
        game.calibrationFinished(round, second.version(), new Outcomes.CalibrationFailed("boom"));
        assertThat(game.views().roundDefinition(round).calibration().status()).isEqualTo(CalibrationStatus.FAILED);
        assertThat(game.views().alerts(null)).extracting(Views.AlertView::code).contains("CALIBRATION_FAILED");

        game.recalibrate(round);
        fx.completeCalibration(round);
        assertThat(game.views().roundDefinition(round).calibration().status()).isEqualTo(CalibrationStatus.READY);
    }

    @Test
    void validationAndPreviews() {
        assertCode(ErrorCode.VALIDATION_FAILED, () -> game.createRound(
                new RoundInput("", "h", null, null, true, null), new TargetChoice.Uploaded("t")));
        assertCode(ErrorCode.VALIDATION_FAILED, () -> game.createRound(
                new RoundInput("n", "h", 59, null, true, null), new TargetChoice.Uploaded("t")));
        assertCode(ErrorCode.VALIDATION_FAILED, () -> game.createRound(
                new RoundInput("n", "h", null, 11, true, null), new TargetChoice.Uploaded("t")));
        assertCode(ErrorCode.VALIDATION_FAILED, () -> game.createRound(
                new RoundInput("n", "h", null, null, true, 2147483648L), new TargetChoice.Uploaded("t")));
        assertCode(ErrorCode.PREVIEW_NOT_FOUND, () -> game.createRound(
                new RoundInput("n", "h", null, null, true, null), new TargetChoice.FromPreview("nope")));

        Views.PreviewView preview = game.registerPreview("img-p", 1234, "fake", "fake-v1");
        Views.RoundDefinitionView round = game.createRound(new RoundInput("n", "h", null, null, false, null),
                new TargetChoice.FromPreview(preview.previewId()));
        assertThat(round.seed()).as("seed from the preview").isEqualTo(1234);
        assertThat(round.durationSec()).as("practice default").isEqualTo(180);
        assertThat(round.attemptsPerPlayer()).isEqualTo(3);
        assertThat(round.targetSource()).isEqualTo(TargetSource.GENERATED);
        assertThat(round.calibration().status()).isEqualTo(CalibrationStatus.NOT_REQUIRED);
        assertCode(ErrorCode.PREVIEW_NOT_FOUND, () -> game.createRound(new RoundInput("n", "h", null, null, false, null),
                new TargetChoice.FromPreview(preview.previewId())));
        assertCode(ErrorCode.INVALID_TRANSITION, () -> game.recalibrate(round.id()));
        game.deleteRound(round.id());
        assertCode(ErrorCode.ROUND_NOT_FOUND, () -> game.updateRound(round.id(),
                new RoundInput(null, null, null, null, null, null), null));
    }
}
