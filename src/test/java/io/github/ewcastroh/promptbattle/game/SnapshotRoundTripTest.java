package io.github.ewcastroh.promptbattle.game;

import static io.github.ewcastroh.promptbattle.game.GameFixture.BOG;
import static io.github.ewcastroh.promptbattle.game.GameFixture.MDE;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ewcastroh.promptbattle.persistence.SnapshotCodec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Jackson 3 round-trip of a busy state (PLAN A1 risk), plus what a restore does with lost work. */
class SnapshotRoundTripTest {

    private final SnapshotCodec codec = new SnapshotCodec();

    @Test
    void busyStateRoundTripsAndKeepsJoinOrder() {
        GameFixture fx = new GameFixture();
        String r1 = fx.scoredRound("R1");
        String zed = fx.join(MDE, "Zed");
        String amy = fx.join(MDE, "Amy");
        fx.game.startRound(MDE, r1);
        String a = fx.done(zed, "x");
        fx.done(amy, "y");
        fx.game.selectEntry(zed, a);
        fx.closeAndScore(MDE, r1, Map.of(a, 0.9));
        fx.game.startMiniPodium(MDE);
        fx.game.nextMiniPodium(MDE);
        fx.join(BOG, "Bob");
        fx.game.startRound(BOG, r1);

        byte[] json = codec.encode(fx.state);
        EventState restored = codec.decode(json);
        assertThat(new String(codec.encode(restored))).isEqualTo(new String(json));
        Game game = new Game(restored, GameFixture.settings(true), fx.clock, Moderation.NONE,
                (k, s) -> new Commentary("es", "en"), QueuePositions.NONE, ProviderHealth.ALL_CLOSED,
                new java.util.SplittableRandom(1));
        assertThat(game.views().players(MDE)).extracting(Views.AdminPlayer::nickname).containsExactly("Zed", "Amy");
        assertThat(game.views().stage(MDE).reveal().entries()).hasSize(1);
        assertThat(game.views().me(zed).player().nickname()).isEqualTo("Zed");
    }

    @Test
    void restoreFailsLostGenerationsWithoutUsingAttemptsAndResumesPendingWork() {
        GameFixture fx = new GameFixture();
        String r1 = fx.scoredRound("R1");
        String ana = fx.join(MDE, "Ana");
        String beto = fx.join(BOG, "Beto");
        fx.game.startRound(MDE, r1);
        String lost = fx.attempt(ana, "in flight at the crash");
        fx.game.startRound(BOG, r1);
        String e = fx.done(beto, "done");
        fx.game.closeRound(BOG, r1); // scoring pending at the crash
        Views.RoundDefinitionView pending = fx.game.createRound(
                new RoundInput("R2", "h", null, null, true, 3L), new TargetChoice.Uploaded("t"));

        EventState restored = codec.decode(codec.encode(fx.state));
        Game game = new Game(restored, GameFixture.settings(true), fx.clock, Moderation.NONE,
                (k, s) -> new Commentary("es", "en"), QueuePositions.NONE, ProviderHealth.ALL_CLOSED,
                new java.util.SplittableRandom(1));
        game.resumeAfterRestore();

        Views.AttemptView attempt = game.views().attempt(ana, lost);
        assertThat(attempt.status()).isEqualTo(AttemptStatus.FAILED);
        assertThat(attempt.code()).isEqualTo(ErrorCode.GENERATION_FAILED);
        assertThat(game.views().me(ana).round().attemptsLeft()).isEqualTo(3);
        List<Effect> effects = game.drainEffects();
        assertThat(effects).filteredOn(Effect.ScoreRun.class::isInstance).singleElement()
                .satisfies(s -> assertThat(((Effect.ScoreRun) s).entryImageIds()).containsOnlyKeys(e));
        assertThat(effects).filteredOn(Effect.Calibrate.class::isInstance).singleElement()
                .satisfies(c -> assertThat(((Effect.Calibrate) c).roundId()).isEqualTo(pending.id()));
    }
}
