package io.github.ewcastroh.promptbattle.game;

import static io.github.ewcastroh.promptbattle.game.GameFixture.MDE;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SPEC §4.3 and D-013 / PLAN A2 EmbeddingModeTest: in {@code entries} mode only entries (plus the target and
 * anchors) are embedded, never other attempts; {@code eager} embeds every successful generation.
 */
class EmbeddingModeTest {

    private static GameSettings settings(boolean eager) {
        return new GameSettings(20, 3, true, eager, GameFixture.ANCHOR_PROMPTS, List.of("fake"), List.of("fake"),
                "https://ui.test/");
    }

    private static String imageOf(GameFixture fx, String round, String attemptId) {
        return fx.run(MDE, round).attempts.get(attemptId).imageId;
    }

    private static List<String> prefetched(GameFixture fx) {
        return fx.take(Effect.PrefetchEmbedding.class).stream().map(Effect.PrefetchEmbedding::imageId).toList();
    }

    @Test
    void entriesModeEmbedsOnlyEntriesAndNeverOtherAttempts() {
        GameFixture fx = new GameFixture(settings(false));
        String round = fx.scoredRound("R1");
        fx.game.startRound(MDE, round);
        fx.take(Effect.PrefetchEmbedding.class); // target and anchors, at start
        String chooser = fx.join(MDE, "Chooser");
        String lazy = fx.join(MDE, "Lazy");

        String chosen = fx.done(chooser, "first idea");
        fx.done(chooser, "second idea"); // never an entry: never embedded
        fx.done(lazy, "one");
        String lazyLast = fx.done(lazy, "two");
        assertThat(prefetched(fx)).as("a successful attempt alone is never embedded").isEmpty();

        fx.game.selectEntry(chooser, chosen);
        assertThat(prefetched(fx)).containsExactly(imageOf(fx, round, chosen));

        fx.game.closeRound(MDE, round);
        assertThat(prefetched(fx)).as("the automatic entry at close")
                .containsExactly(imageOf(fx, round, lazyLast));
        assertThat(fx.take(Effect.ScoreRun.class)).singleElement()
                .satisfies(s -> assertThat(s.entryImageIds()).containsOnlyKeys(chosen, lazyLast));
    }

    @Test
    void eagerModeEmbedsEverySuccessfulGeneration() {
        GameFixture fx = new GameFixture(settings(true));
        String round = fx.scoredRound("R1");
        fx.game.startRound(MDE, round);
        fx.take(Effect.PrefetchEmbedding.class);
        String player = fx.join(MDE, "Eager");

        String first = fx.done(player, "first");
        String second = fx.done(player, "second");
        fx.fail(fx.attempt(player, "fails"), FailureKind.TIMEOUT);

        assertThat(prefetched(fx)).containsExactly(imageOf(fx, round, first), imageOf(fx, round, second));
        fx.game.selectEntry(player, first);
        assertThat(prefetched(fx)).as("already embedded when it was generated").isEmpty();
    }

    @Test
    void practiceRoundsAreNeverEmbedded() {
        GameFixture fx = new GameFixture(settings(true));
        String practice = fx.practiceRound();
        String player = fx.join(MDE, "Practice");
        fx.game.startRound(MDE, practice);
        String id = fx.done(player, "anything");
        fx.game.selectEntry(player, id);
        fx.game.closeRound(MDE, practice);

        assertThat(fx.take(Effect.PrefetchEmbedding.class)).isEmpty();
        assertThat(fx.take(Effect.ScoreRun.class)).isEmpty();
    }
}
