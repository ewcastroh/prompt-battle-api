package io.github.ewcastroh.promptbattle.game;

import static io.github.ewcastroh.promptbattle.game.GameFixture.BOG;
import static io.github.ewcastroh.promptbattle.game.GameFixture.MDE;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** SPEC §2, §14.1-1: every command on one city leaves the other city's state byte-identical. */
class CityIsolationTest {

    private final GameFixture fx = new GameFixture();
    private final Game game = fx.game;

    private void onMde(Runnable command) {
        String before = fx.cityJson(BOG);
        command.run();
        assertThat(fx.cityJson(BOG)).isEqualTo(before);
    }

    @Test
    void everyCommandOnMedellinLeavesBogotaUnchanged() {
        String practice = fx.practiceRound();
        String r1 = fx.scoredRound("R1");
        String r2 = fx.scoredRound("R2");

        // Bogotá: mid-round with a generation in flight and an entry.
        String bogPlayer = fx.join(BOG, "Ana");
        String bogOther = fx.join(BOG, "Beto");
        game.startRound(BOG, r1);
        String bogEntry = fx.done(bogPlayer, "bogota entry");
        game.selectEntry(bogPlayer, bogEntry);
        fx.attempt(bogOther, "in flight");
        fx.clearEffects();

        String[] tokens = new String[6];
        onMde(() -> tokens[0] = fx.join(MDE, "Ana")); // same nickname as in Bogotá: allowed
        onMde(() -> tokens[1] = fx.join(MDE, "Carla"));
        onMde(() -> game.startRound(MDE, practice));
        String[] ids = new String[6];
        onMde(() -> ids[0] = fx.attempt(tokens[0], "practice"));
        onMde(() -> fx.succeed(ids[0]));
        onMde(() -> game.extendRound(MDE, practice));
        onMde(() -> game.closeRound(MDE, practice));
        onMde(() -> game.showPracticeGallery(MDE, true));
        onMde(() -> game.showPracticeGallery(MDE, false));
        onMde(() -> game.startRound(MDE, r2));
        onMde(() -> ids[1] = fx.done(tokens[0], "first"));
        onMde(() -> ids[2] = fx.attempt(tokens[1], "blocked by provider"));
        onMde(() -> fx.fail(ids[2], FailureKind.SAFETY_BLOCKED));
        onMde(() -> ids[3] = fx.done(tokens[1], "second"));
        onMde(() -> game.selectEntry(tokens[0], ids[1]));
        onMde(() -> {
            fx.clock.advanceSeconds(1);
            game.tick();
        });
        onMde(() -> game.closeRound(MDE, r2));
        onMde(() -> fx.score(MDE, r2, Map.of(ids[1], 0.9, ids[3], 0.4)));
        onMde(fx::answerCommentary);
        onMde(() -> game.hideEntry(MDE, ids[3], true));
        onMde(() -> game.hideEntry(MDE, ids[3], false));
        onMde(() -> game.renamePlayer(MDE, fx.playerId(tokens[1]), "Carla_2"));
        onMde(() -> game.startMiniPodium(MDE));
        onMde(() -> game.nextMiniPodium(MDE));
        onMde(() -> game.startPodium(MDE));
        onMde(fx::answerCommentary);
        onMde(() -> game.nextPodium(MDE));
        onMde(() -> game.resetPodium(MDE));
        onMde(() -> game.kickPlayer(MDE, fx.playerId(tokens[1])));

        assertThat(fx.phase(BOG)).isEqualTo(Phase.ROUND_OPEN);
        assertThat(fx.phase(MDE)).isEqualTo(Phase.PODIUM_REVEAL);
    }

    @Test
    void adminActionsOnAnotherCitysResourcesAreForbidden() {
        String r1 = fx.scoredRound("R1");
        String bogPlayer = fx.join(BOG, "Ana");
        game.startRound(BOG, r1);
        String entry = fx.done(bogPlayer, "x");
        game.selectEntry(bogPlayer, entry);
        String bogPlayerId = fx.playerId(bogPlayer);
        String before = fx.cityJson(BOG);

        GameAssertions.assertCode(ErrorCode.ADMIN_FORBIDDEN, () -> game.kickPlayer(MDE, bogPlayerId));
        GameAssertions.assertCode(ErrorCode.ADMIN_FORBIDDEN, () -> game.renamePlayer(MDE, bogPlayerId, "Hacker"));
        GameAssertions.assertCode(ErrorCode.ADMIN_FORBIDDEN, () -> game.hideEntry(MDE, entry, true));
        GameAssertions.assertCode(ErrorCode.INVALID_TRANSITION, () -> game.closeRound(MDE, r1));
        GameAssertions.assertCode(ErrorCode.PLAYER_NOT_FOUND, () -> game.kickPlayer(MDE, "nobody"));
        GameAssertions.assertCode(ErrorCode.ENTRY_NOT_FOUND, () -> game.hideEntry(MDE, "nothing", true));
        assertThat(fx.cityJson(BOG)).isEqualTo(before);
    }

    @Test
    void aTokenBelongsToExactlyOneCity() {
        String bogToken = fx.join(BOG, "Ana");
        Views.JoinResult mde = game.join(MDE, "Ana", bogToken);
        assertThat(mde.created()).as("a token from another city is ignored").isTrue();
        assertThat(mde.body().city()).isEqualTo(MDE);
        assertThat(mde.body().token()).isNotEqualTo(bogToken);
        Views.JoinResult again = game.join(BOG, "Whatever", bogToken);
        assertThat(again.created()).isFalse();
        assertThat(again.body().playerId()).isEqualTo(fx.playerId(bogToken));
    }
}
