package io.github.ewcastroh.promptbattle.game;

import static io.github.ewcastroh.promptbattle.game.GameAssertions.assertCode;
import static io.github.ewcastroh.promptbattle.game.GameFixture.MDE;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/** Every row of API.md §4, and 409 INVALID_TRANSITION for every action not allowed in a phase. */
class GameEngineTransitionsTest {

    private final GameFixture fx = new GameFixture();
    private final Game game = fx.game;

    private enum Action { START, CLOSE, EXTEND, MINI_START, MINI_NEXT, PODIUM_START, PODIUM_NEXT, PODIUM_RESET, GALLERY }

    private void assertOnlyAllowed(String freshRound, String currentRound, Action... allowed) {
        Phase before = fx.phase(MDE);
        String json = fx.cityJson(MDE);
        Map<Action, ThrowingCallable> actions = new LinkedHashMap<>();
        actions.put(Action.START, () -> game.startRound(MDE, freshRound));
        actions.put(Action.CLOSE, () -> game.closeRound(MDE, currentRound));
        actions.put(Action.EXTEND, () -> game.extendRound(MDE, currentRound));
        actions.put(Action.MINI_START, () -> game.startMiniPodium(MDE));
        actions.put(Action.MINI_NEXT, () -> game.nextMiniPodium(MDE));
        actions.put(Action.PODIUM_START, () -> game.startPodium(MDE));
        actions.put(Action.PODIUM_NEXT, () -> game.nextPodium(MDE));
        actions.put(Action.PODIUM_RESET, () -> game.resetPodium(MDE));
        actions.put(Action.GALLERY, () -> game.showPracticeGallery(MDE, true));
        List<Action> allowedList = List.of(allowed);
        actions.forEach((action, call) -> {
            if (!allowedList.contains(action)) {
                assertCode(ErrorCode.INVALID_TRANSITION, call);
            }
        });
        assertThat(fx.phase(MDE)).isEqualTo(before);
        assertThat(fx.cityJson(MDE)).as("rejected actions leave the state unchanged").isEqualTo(json);
    }

    @Test
    void fullCityLifecycleFollowsTheTransitionTable() {
        String practice = fx.practiceRound();
        String r1 = fx.scoredRound("R1");
        String r2 = fx.scoredRound("R2");
        String ana = fx.join(MDE, "Ana");

        // LOBBY: nothing scored, no practice played yet
        assertThat(fx.phase(MDE)).isEqualTo(Phase.LOBBY);
        assertOnlyAllowed(practice, practice, Action.START);

        // LOBBY → ROUND_OPEN (practice)
        game.startRound(MDE, practice);
        assertThat(fx.phase(MDE)).isEqualTo(Phase.ROUND_OPEN);
        assertOnlyAllowed(r1, practice, Action.CLOSE, Action.EXTEND);
        assertCode(ErrorCode.INVALID_TRANSITION, () -> game.closeRound(MDE, r1));

        // ROUND_OPEN → ROUND_CLOSING with a generation in flight
        String inFlight = fx.attempt(ana, "a red apple");
        game.closeRound(MDE, practice);
        assertThat(fx.phase(MDE)).isEqualTo(Phase.ROUND_CLOSING);
        assertOnlyAllowed(r1, practice);

        // practice ROUND_CLOSING → LOBBY once nothing is in flight
        fx.succeed(inFlight);
        assertThat(fx.phase(MDE)).isEqualTo(Phase.LOBBY);
        assertThat(fx.run(MDE, practice).status).isEqualTo(RunStatus.CLOSED);
        assertOnlyAllowed(r1, practice, Action.START, Action.GALLERY);
        assertCode(ErrorCode.INVALID_TRANSITION, () -> game.startRound(MDE, practice));

        // LOBBY → ROUND_OPEN (scored) → ROUND_CLOSING → ROUND_SCORED after scoring and commentary
        game.startRound(MDE, r1);
        String entry = fx.done(ana, "an apple");
        game.closeRound(MDE, r1);
        assertThat(fx.phase(MDE)).isEqualTo(Phase.ROUND_CLOSING);
        assertThat(fx.run(MDE, r1).status).isEqualTo(RunStatus.SCORING);
        fx.score(MDE, r1, Map.of(entry, 0.8));
        assertThat(fx.phase(MDE)).as("waits for mini-podium commentary").isEqualTo(Phase.ROUND_CLOSING);
        fx.answerCommentary();
        assertThat(fx.phase(MDE)).isEqualTo(Phase.ROUND_SCORED);
        assertThat(fx.run(MDE, r1).status).isEqualTo(RunStatus.SCORED);
        assertOnlyAllowed(r2, r1, Action.START, Action.MINI_START, Action.PODIUM_START);

        // ROUND_SCORED → MINI_PODIUM step 0 → step 1 (size 1) → 409 at size
        game.startMiniPodium(MDE);
        Reveal mini = fx.state.cities.get(MDE).miniPodium;
        assertThat(fx.phase(MDE)).isEqualTo(Phase.MINI_PODIUM);
        assertThat(mini.step).isZero();
        assertThat(mini.size).isEqualTo(1);
        game.nextMiniPodium(MDE);
        assertThat(mini.step).isEqualTo(1);
        assertOnlyAllowed(r2, r1, Action.START, Action.PODIUM_START);

        // MINI_PODIUM → FINAL_SCORING → PODIUM_REVEAL step 0 when comments are ready
        game.startPodium(MDE);
        assertThat(fx.phase(MDE)).isEqualTo(Phase.FINAL_SCORING);
        assertOnlyAllowed(r2, r1);
        fx.answerCommentary();
        assertThat(fx.phase(MDE)).isEqualTo(Phase.PODIUM_REVEAL);
        assertThat(fx.state.cities.get(MDE).podium.step).isZero();
        assertOnlyAllowed(r2, r1, Action.PODIUM_NEXT, Action.PODIUM_RESET);

        // PODIUM_REVEAL step + 1; after step = size → FINISHED
        game.nextPodium(MDE);
        assertThat(fx.state.cities.get(MDE).podium.step).isEqualTo(1);
        assertThat(fx.phase(MDE)).isEqualTo(Phase.PODIUM_REVEAL);
        game.nextPodium(MDE);
        assertThat(fx.phase(MDE)).isEqualTo(Phase.FINISHED);
        assertOnlyAllowed(r2, r1, Action.PODIUM_RESET);

        // PODIUM_REVEAL, FINISHED → reset → PODIUM_REVEAL step 0
        game.resetPodium(MDE);
        assertThat(fx.phase(MDE)).isEqualTo(Phase.PODIUM_REVEAL);
        assertThat(fx.state.cities.get(MDE).podium.step).isZero();
        assertThat(fx.state.cities.get(MDE).podium.revealed).isEmpty();
    }

    @Test
    void roundCanStartFromMiniPodiumAndPodiumFromLobbyAfterAScoredRound() {
        String r1 = fx.scoredRound("R1");
        String r2 = fx.scoredRound("R2");
        String practice = fx.practiceRound();
        String ana = fx.join(MDE, "Ana");
        game.startRound(MDE, r1);
        String e1 = fx.done(ana, "one");
        fx.closeAndScore(MDE, r1, Map.of(e1, 0.5));
        game.startMiniPodium(MDE);
        game.startRound(MDE, r2);
        assertThat(fx.phase(MDE)).isEqualTo(Phase.ROUND_OPEN);
        assertThat(fx.state.cities.get(MDE).miniPodium).isNull();
        game.closeRound(MDE, r2);
        fx.score(MDE, r2, Map.of());
        fx.answerCommentary();
        game.startRound(MDE, practice);
        game.closeRound(MDE, practice);
        assertThat(fx.phase(MDE)).isEqualTo(Phase.LOBBY);
        game.startPodium(MDE);
        assertThat(fx.phase(MDE)).isIn(Phase.FINAL_SCORING, Phase.PODIUM_REVEAL);
    }

    @Test
    void scoredRoundNeedsReadyCalibrationAndAProvider() {
        Views.RoundDefinitionView pending = game.createRound(
                new RoundInput("R", "h", 480, 3, true, 1L), new TargetChoice.Uploaded("t"));
        assertThat(pending.calibration().status()).isEqualTo(CalibrationStatus.PENDING);
        assertCode(ErrorCode.ROUND_NOT_READY, () -> game.startRound(MDE, pending.id()));
        assertCode(ErrorCode.ROUND_NOT_FOUND, () -> game.startRound(MDE, "nope"));

        GameSettings none = new GameSettings(20, 3, true, false, List.of("p"), List.of(), List.of(), "https://x");
        GameFixture noProviders = new GameFixture(none);
        String practice = noProviders.practiceRound();
        assertCode(ErrorCode.PROVIDER_UNAVAILABLE, () -> noProviders.game.startRound(MDE, practice));
        Views.RoundDefinitionView scored = noProviders.game.createRound(
                new RoundInput("R", "h", 480, 3, true, 1L), new TargetChoice.Uploaded("t"));
        assertThat(scored.calibration().status()).isEqualTo(CalibrationStatus.FAILED);
    }

    @Test
    void timerClosesTheRunAtEndsAtAndExtendAddsSixtySeconds() {
        String r1 = fx.scoredRound("R1");
        game.startRound(MDE, r1);
        RoundRun run = fx.run(MDE, r1);
        assertThat(run.endsAt).isEqualTo(run.startedAt.plusSeconds(480));

        game.extendRound(MDE, r1);
        assertThat(run.endsAt).isEqualTo(run.startedAt.plusSeconds(540));
        assertThat(run.extensions).isEqualTo(1);

        fx.clock.advanceSeconds(480);
        game.tick();
        assertThat(fx.phase(MDE)).as("extended: still open at the old endsAt").isEqualTo(Phase.ROUND_OPEN);

        fx.clock.advanceSeconds(60);
        game.tick();
        assertThat(fx.phase(MDE)).isEqualTo(Phase.ROUND_CLOSING);
        assertThat(run.status).isEqualTo(RunStatus.SCORING);
        assertCode(ErrorCode.INVALID_TRANSITION, () -> game.extendRound(MDE, r1));
    }

    @Test
    void scoringFailureKeepsClosingRaisesAlertAndRescoreRetries() {
        String r1 = fx.scoredRound("R1");
        String ana = fx.join(MDE, "Ana");
        game.startRound(MDE, r1);
        String entry = fx.done(ana, "x");
        game.closeRound(MDE, r1);
        assertCode(ErrorCode.SCORING_FAILED, () -> game.rescore(MDE, r1));
        fx.take(Effect.ScoreRun.class);
        game.scoringFinished(MDE, r1, new Outcomes.ScoringFailed("boom"));

        assertThat(fx.phase(MDE)).isEqualTo(Phase.ROUND_CLOSING);
        assertThat(fx.run(MDE, r1).status).isEqualTo(RunStatus.SCORING_FAILED);
        assertThat(game.views().alerts(null)).extracting(Views.AlertView::code).containsExactly("SCORING_FAILED");
        assertThat(game.views().alerts(null).getFirst().city()).isEqualTo(MDE);

        game.rescore(MDE, r1);
        assertThat(fx.run(MDE, r1).status).isEqualTo(RunStatus.SCORING);
        fx.score(MDE, r1, Map.of(entry, 0.7));
        fx.answerCommentary();
        assertThat(fx.phase(MDE)).isEqualTo(Phase.ROUND_SCORED);
    }

    @Test
    void miniPodiumDisabledRejectsStartAndSkipsCommentaryWait() {
        GameFixture off = new GameFixture(GameFixture.settings(false));
        String r1 = off.scoredRound("R1");
        String ana = off.join(MDE, "Ana");
        off.game.startRound(MDE, r1);
        String e = off.done(ana, "x");
        off.game.closeRound(MDE, r1);
        off.score(MDE, r1, Map.of(e, 0.4));
        assertThat(off.phase(MDE)).isEqualTo(Phase.ROUND_SCORED);
        assertCode(ErrorCode.INVALID_TRANSITION, () -> off.game.startMiniPodium(MDE));
    }

    @Test
    void podiumRevealsRanksFiveToOneOneStepAtATime() {
        String r1 = fx.scoredRound("R1");
        Map<String, Double> cos = new LinkedHashMap<>();
        game.startRound(MDE, r1);
        for (int i = 1; i <= 7; i++) {
            String token = fx.join(MDE, "Player" + i);
            cos.put(fx.done(token, "p" + i), i / 10.0);
        }
        fx.closeAndScore(MDE, r1, cos);
        game.startPodium(MDE);
        fx.answerCommentary();
        Consumer<Integer> next = expectedRank -> {
            game.nextPodium(MDE);
            Reveal podium = fx.state.cities.get(MDE).podium;
            assertThat(podium.revealed.getLast().rank).isEqualTo(expectedRank);
        };
        for (int rank = 5; rank >= 1; rank--) {
            next.accept(rank);
        }
        List<String> order = fx.state.cities.get(MDE).podium.revealed.stream()
                .map(r -> fx.state.cities.get(MDE).players.get(r.playerId).nickname).toList();
        assertThat(order).containsExactly("Player3", "Player4", "Player5", "Player6", "Player7");
        game.nextPodium(MDE);
        assertThat(fx.phase(MDE)).isEqualTo(Phase.FINISHED);
    }
}
