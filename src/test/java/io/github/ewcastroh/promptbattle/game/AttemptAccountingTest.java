package io.github.ewcastroh.promptbattle.game;

import static io.github.ewcastroh.promptbattle.game.GameAssertions.assertCode;
import static io.github.ewcastroh.promptbattle.game.GameFixture.MDE;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** SPEC §3.3: a success consumes an attempt; failures and blocks don't; one generation in flight. */
class AttemptAccountingTest {

    private final GameFixture fx = new GameFixture();
    private final Game game = fx.game;
    private String token;
    private String round;

    @BeforeEach
    void openRound() {
        round = fx.scoredRound("R1");
        token = fx.join(MDE, "Ana");
        game.startRound(MDE, round);
        fx.clearEffects();
    }

    private int attemptsLeft() {
        return game.views().me(token).round().attemptsLeft();
    }

    @Test
    void successConsumesFailureAndBlocksDoNot() {
        assertThat(attemptsLeft()).isEqualTo(3);
        fx.done(token, "one");
        assertThat(attemptsLeft()).isEqualTo(2);

        for (FailureKind kind : new FailureKind[] { FailureKind.TIMEOUT, FailureKind.PROVIDER_ERROR,
                FailureKind.RATE_LIMITED, FailureKind.INVALID_RESPONSE, FailureKind.SAFETY_BLOCKED }) {
            fx.fail(fx.attempt(token, "fails " + kind), kind);
        }
        assertThat(attemptsLeft()).isEqualTo(2);

        fx.blockedWords.add("forbidden");
        assertCode(ErrorCode.PROMPT_BLOCKED, () -> fx.attempt(token, "a forbidden thing"));
        assertThat(attemptsLeft()).isEqualTo(2);

        fx.done(token, "two");
        fx.done(token, "three");
        assertThat(attemptsLeft()).isZero();
        assertCode(ErrorCode.NO_ATTEMPTS_LEFT, () -> fx.attempt(token, "four"));
    }

    @Test
    void attemptStatusesAndCodes() {
        String failed = fx.attempt(token, "x");
        fx.fail(failed, FailureKind.TIMEOUT);
        String blocked = fx.attempt(token, "y");
        fx.fail(blocked, FailureKind.SAFETY_BLOCKED);
        String queued = fx.attempt(token, "z");

        var attempts = game.views().me(token).attempts();
        assertThat(attempts).extracting(Views.AttemptView::status)
                .containsExactly(AttemptStatus.FAILED, AttemptStatus.BLOCKED, AttemptStatus.QUEUED);
        assertThat(attempts).extracting(Views.AttemptView::code)
                .containsExactly(ErrorCode.GENERATION_FAILED, ErrorCode.PROMPT_BLOCKED, null);
        game.attemptStarted(queued);
        assertThat(game.views().attempt(token, queued).status()).isEqualTo(AttemptStatus.GENERATING);
    }

    @Test
    void onlyOneGenerationInFlight() {
        String first = fx.attempt(token, "one");
        assertThat(fx.take(Effect.GenerateAttempt.class)).hasSize(1);
        assertCode(ErrorCode.GENERATION_IN_FLIGHT, () -> fx.attempt(token, "two"));
        game.attemptStarted(first);
        assertCode(ErrorCode.GENERATION_IN_FLIGHT, () -> fx.attempt(token, "two"));
        fx.game.attemptFinished(first, new Outcomes.Generated("img", "fake", "m"));
        assertThat(fx.attempt(token, "two")).isNotBlank();
    }

    @Test
    void threeBlocksInARoundFlagThePlayerOnce() {
        fx.blockedWords.add("bad");
        assertCode(ErrorCode.PROMPT_BLOCKED, () -> fx.attempt(token, "bad one"));
        fx.fail(fx.attempt(token, "provider says no"), FailureKind.SAFETY_BLOCKED);
        assertThat(game.views().players(MDE).getFirst().flagged()).isFalse();
        assertCode(ErrorCode.PROMPT_BLOCKED, () -> fx.attempt(token, "bad three"));

        Views.AdminPlayer p = game.views().players(MDE).getFirst();
        assertThat(p.flagged()).isTrue();
        assertThat(p.blockedCount()).isEqualTo(3);
        assertCode(ErrorCode.PROMPT_BLOCKED, () -> fx.attempt(token, "bad four"));
        assertThat(game.views().alerts(null)).filteredOn(a -> a.code().equals("PLAYER_FLAGGED")).hasSize(1);
    }

    @Test
    void promptIsNormalizedAndValidated() {
        String id = fx.attempt(token, "   a   red \n\t bicycle  ");
        assertThat(game.views().attempt(token, id).prompt()).isEqualTo("a red bicycle");
        fx.succeed(id);
        assertCode(ErrorCode.PROMPT_INVALID, () -> fx.attempt(token, "   "));
        assertCode(ErrorCode.PROMPT_INVALID, () -> fx.attempt(token, "x".repeat(301)));
        assertThat(fx.attempt(token, "x".repeat(300))).isNotBlank();
    }

    @Test
    void writesNeedAnOpenRoundAndAValidPlayer() {
        assertCode(ErrorCode.UNAUTHORIZED, () -> fx.attempt("pt_unknown", "x"));
        assertCode(ErrorCode.UNAUTHORIZED, () -> fx.attempt(null, "x"));
        String done = fx.done(token, "x");
        game.closeRound(MDE, round);
        assertCode(ErrorCode.ROUND_NOT_OPEN, () -> fx.attempt(token, "late"));
        assertCode(ErrorCode.ROUND_NOT_OPEN, () -> game.selectEntry(token, done));

        String other = fx.join(MDE, "Beto");
        game.kickPlayer(MDE, fx.playerId(other));
        assertCode(ErrorCode.PLAYER_KICKED, () -> fx.attempt(other, "x"));
        assertCode(ErrorCode.PLAYER_KICKED, () -> game.views().me(other));
    }

    @Test
    void entrySelectionRules() {
        String failed = fx.attempt(token, "fail");
        fx.fail(failed, FailureKind.TIMEOUT);
        assertCode(ErrorCode.ATTEMPT_NOT_SELECTABLE, () -> game.selectEntry(token, failed));
        assertCode(ErrorCode.ATTEMPT_NOT_FOUND, () -> game.selectEntry(token, "nope"));

        String other = fx.join(MDE, "Beto");
        String othersAttempt = fx.done(other, "theirs");
        assertCode(ErrorCode.ATTEMPT_NOT_FOUND, () -> game.selectEntry(token, othersAttempt));
        assertCode(ErrorCode.ATTEMPT_NOT_FOUND, () -> game.views().attempt(token, othersAttempt));

        String a = fx.done(token, "a");
        String b = fx.done(token, "b");
        game.selectEntry(token, a);
        assertThat(game.views().me(token).round().entryAttemptId()).isEqualTo(a);
        game.selectEntry(token, b);
        assertThat(game.views().me(token).round().entryAttemptId()).isEqualTo(b);
        assertThat(fx.run(MDE, round).entries.get(fx.playerId(token)).auto).isFalse();
    }

    @Test
    void entriesModeEmbedsOnlySelectedAttempts() {
        String a = fx.done(token, "a");
        assertThat(fx.take(Effect.PrefetchEmbedding.class)).isEmpty();
        game.selectEntry(token, a);
        assertThat(fx.take(Effect.PrefetchEmbedding.class)).hasSize(1);
    }
}
