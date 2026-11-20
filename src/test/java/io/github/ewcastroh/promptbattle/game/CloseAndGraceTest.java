package io.github.ewcastroh.promptbattle.game;

import static io.github.ewcastroh.promptbattle.game.GameAssertions.assertCode;
import static io.github.ewcastroh.promptbattle.game.GameFixture.MDE;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ewcastroh.promptbattle.game.Outcomes.Generated;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** SPEC §3.3 and D-018: auto-entry at close, grace period, when scoring starts. */
class CloseAndGraceTest {

    private final GameFixture fx = new GameFixture();
    private final Game game = fx.game;
    private String round;

    @BeforeEach
    void openRound() {
        round = fx.scoredRound("R1");
        game.startRound(MDE, round);
    }

    private Entry entryOf(String token) {
        return fx.run(MDE, round).entries.get(fx.playerId(token));
    }

    @Test
    void autoEntryIsTheLastSuccessfulAttemptAndManualEntriesAreKept() {
        String auto = fx.join(MDE, "Auto");
        String manual = fx.join(MDE, "Manual");
        String nothing = fx.join(MDE, "Nothing");
        fx.done(auto, "first");
        String last = fx.done(auto, "second");
        fx.fail(fx.attempt(auto, "third fails"), FailureKind.TIMEOUT);
        String chosen = fx.done(manual, "chosen");
        fx.done(manual, "later, not chosen");
        game.selectEntry(manual, chosen);
        fx.fail(fx.attempt(nothing, "fails"), FailureKind.PROVIDER_ERROR);

        fx.clock.advanceSeconds(5);
        game.closeRound(MDE, round);

        assertThat(entryOf(auto).attemptId).isEqualTo(last);
        assertThat(entryOf(auto).auto).isTrue();
        assertThat(entryOf(auto).selectedAt).isEqualTo(fx.run(MDE, round).closedAt);
        assertThat(entryOf(manual).attemptId).isEqualTo(chosen);
        assertThat(entryOf(manual).auto).isFalse();
        assertThat(entryOf(nothing)).isNull();
        assertThat(fx.run(MDE, round).status).as("nothing in flight: scoring starts at once")
                .isEqualTo(RunStatus.SCORING);
        assertThat(fx.take(Effect.ScoreRun.class)).singleElement()
                .satisfies(s -> assertThat(s.entryImageIds()).containsOnlyKeys(last, chosen));
    }

    @Test
    void generationFinishingWithinGraceCountsAndScoringStartsWhenIdle() {
        String late = fx.join(MDE, "Late");
        String id = fx.attempt(late, "slow");
        game.attemptStarted(id);
        game.closeRound(MDE, round);
        assertThat(fx.run(MDE, round).status).isEqualTo(RunStatus.CLOSING);
        assertThat(fx.take(Effect.ScoreRun.class)).isEmpty();
        assertCode(ErrorCode.ROUND_NOT_OPEN, () -> fx.attempt(late, "new"));

        fx.clock.advanceSeconds(19);
        game.tick();
        assertThat(fx.run(MDE, round).status).isEqualTo(RunStatus.CLOSING);
        game.attemptFinished(id, new Generated("img-late", "fake", "m"));

        assertThat(game.views().attempt(late, id).status()).isEqualTo(AttemptStatus.DONE);
        assertThat(entryOf(late).attemptId).as("grace result becomes the auto-entry").isEqualTo(id);
        assertThat(entryOf(late).auto).isTrue();
        assertThat(fx.run(MDE, round).status).isEqualTo(RunStatus.SCORING);
        assertThat(fx.take(Effect.ScoreRun.class)).hasSize(1);
    }

    @Test
    void graceResultDoesNotReplaceAnExistingEntry() {
        String p = fx.join(MDE, "Ana");
        String first = fx.done(p, "first");
        String slow = fx.attempt(p, "slow");
        game.closeRound(MDE, round);
        assertThat(entryOf(p).attemptId).isEqualTo(first);
        game.attemptFinished(slow, new Generated("img-slow", "fake", "m"));
        assertThat(entryOf(p).attemptId).isEqualTo(first);
    }

    @Test
    void resultAfterGraceIsDiscarded() {
        String p = fx.join(MDE, "Ana");
        String id = fx.attempt(p, "too slow");
        fx.clearEffects();
        game.closeRound(MDE, round);

        fx.clock.advanceSeconds(20);
        game.tick();

        assertThat(game.views().attempt(p, id).status()).isEqualTo(AttemptStatus.FAILED);
        assertThat(game.views().attempt(p, id).code()).isEqualTo(ErrorCode.ROUND_NOT_OPEN);
        assertThat(fx.take(Effect.CancelGeneration.class)).extracting(Effect.CancelGeneration::attemptId)
                .containsExactly(id);
        assertThat(fx.run(MDE, round).status).isEqualTo(RunStatus.SCORING);

        game.attemptFinished(id, new Generated("img-too-late", "fake", "m"));
        assertThat(game.views().attempt(p, id).status()).isEqualTo(AttemptStatus.FAILED);
        assertThat(entryOf(p)).isNull();
    }

    @Test
    void timerCloseAppliesTheSameRules() {
        String p = fx.join(MDE, "Ana");
        String done = fx.done(p, "x");
        fx.clock.advanceSeconds(480);
        game.tick();
        assertThat(fx.phase(MDE)).isEqualTo(Phase.ROUND_CLOSING);
        assertThat(entryOf(p).attemptId).isEqualTo(done);
        assertThat(entryOf(p).auto).isTrue();
    }

    @Test
    void practiceReturnsToLobbyWhenGraceEnds() {
        GameFixture other = new GameFixture();
        String practice = other.practiceRound();
        String p = other.join(MDE, "Ana");
        other.game.startRound(MDE, practice);
        other.attempt(p, "slow");
        other.game.closeRound(MDE, practice);
        assertThat(other.phase(MDE)).isEqualTo(Phase.ROUND_CLOSING);
        other.clock.advanceSeconds(20);
        other.game.tick();
        assertThat(other.phase(MDE)).isEqualTo(Phase.LOBBY);
        assertThat(other.run(MDE, practice).status).isEqualTo(RunStatus.CLOSED);
        assertThat(other.take(Effect.ScoreRun.class)).isEmpty();
    }
}
