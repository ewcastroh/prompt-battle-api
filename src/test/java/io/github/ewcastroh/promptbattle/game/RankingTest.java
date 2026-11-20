package io.github.ewcastroh.promptbattle.game;

import static io.github.ewcastroh.promptbattle.game.GameFixture.MDE;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** SPEC §3.4, API.md §4, D-015, D-020: totals, both tie-breaks, exclusions, podium sizes, frozen reveals. */
class RankingTest {

    private final GameFixture fx = new GameFixture();
    private final Game game = fx.game;
    private String r1;
    private String r2;

    @BeforeEach
    void rounds() {
        r1 = fx.scoredRound("R1");
        r2 = fx.scoredRound("R2");
    }

    private List<String> ranking() {
        return Ranking.finalRanking(fx.state, fx.state.cities.get(MDE)).stream().map(s -> s.player().nickname)
                .toList();
    }

    /** Plays a round where each token selects one attempt with the given cosine, in map order. */
    private Map<String, String> play(String round, Map<String, Double> cosByToken) {
        game.startRound(MDE, round);
        Map<String, String> attempts = new LinkedHashMap<>();
        Map<String, Double> cos = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : cosByToken.entrySet()) {
            String id = fx.done(e.getKey(), "prompt");
            fx.clock.advanceSeconds(1);
            game.selectEntry(e.getKey(), id);
            attempts.put(e.getKey(), id);
            cos.put(id, e.getValue());
        }
        fx.closeAndScore(MDE, round, cos);
        return attempts;
    }

    @Test
    void totalsSumScoredRoundsAndMissingEntriesCountZero() {
        String ana = fx.join(MDE, "Ana");
        String beto = fx.join(MDE, "Beto");
        String cata = fx.join(MDE, "Cata");
        play(r1, Map.of(ana, 0.5, beto, 0.9));
        play(r2, Map.of(ana, 0.6, cata, 0.2));
        assertThat(ranking()).containsExactly("Ana", "Beto", "Cata");
        var standings = Ranking.finalRanking(fx.state, fx.state.cities.get(MDE));
        assertThat(standings.getFirst().total()).isEqualTo(110.0);
        assertThat(standings.get(1).total()).isEqualTo(90.0);
    }

    @Test
    void tieBreakOneIsTheHighestSingleRoundScore() {
        String ana = fx.join(MDE, "Ana");
        String beto = fx.join(MDE, "Beto");
        Map<String, Double> first = new LinkedHashMap<>();
        first.put(ana, 0.5);
        first.put(beto, 0.7);
        play(r1, first);
        Map<String, Double> second = new LinkedHashMap<>();
        second.put(ana, 0.5);
        second.put(beto, 0.3);
        play(r2, second);
        // Both total 100.0; Beto's best round (70) beats Ana's (50) although Ana selected first.
        assertThat(ranking()).containsExactly("Beto", "Ana");
    }

    @Test
    void tieBreakTwoIsTheEarliestSelectedAtOfTheLastEntry() {
        String ana = fx.join(MDE, "Ana");
        String beto = fx.join(MDE, "Beto");
        Map<String, Double> first = new LinkedHashMap<>();
        first.put(ana, 0.6);
        first.put(beto, 0.6);
        play(r1, first);
        Map<String, Double> second = new LinkedHashMap<>();
        second.put(beto, 0.4); // Beto selects first in the last round
        second.put(ana, 0.4);
        play(r2, second);
        assertThat(ranking()).containsExactly("Beto", "Ana");
    }

    @Test
    void hiddenEntriesScoreZeroAndKickedPlayersAreExcluded() {
        String ana = fx.join(MDE, "Ana");
        String beto = fx.join(MDE, "Beto");
        String cata = fx.join(MDE, "Cata");
        Map<String, String> attempts = play(r1, Map.of(ana, 0.9, beto, 0.5, cata, 0.4));
        game.hideEntry(MDE, attempts.get(ana), true);
        assertThat(ranking()).containsExactly("Beto", "Cata");
        game.kickPlayer(MDE, fx.playerId(beto));
        assertThat(ranking()).containsExactly("Cata");
        game.hideEntry(MDE, attempts.get(ana), false);
        assertThat(ranking()).containsExactly("Ana", "Cata");
    }

    @Test
    void podiumSizeIsMinOfFiveAndEligiblePlayers() {
        String ana = fx.join(MDE, "Ana");
        String beto = fx.join(MDE, "Beto");
        fx.join(MDE, "NoEntry");
        play(r1, Map.of(ana, 0.9, beto, 0.5));
        game.startPodium(MDE);
        fx.answerCommentary();
        assertThat(fx.state.cities.get(MDE).podium.size).isEqualTo(2);
        game.nextPodium(MDE);
        game.nextPodium(MDE);
        Views.Stage stage = game.views().stage(MDE);
        assertThat(stage.reveal().entries()).extracting(Views.RevealEntry::rank).containsExactly(2, 1);
        assertThat(stage.reveal().entries()).extracting(Views.RevealEntry::nickname).containsExactly("Beto", "Ana");
    }

    @Test
    void finalPodiumShowsTheBestEntryTieGoesToTheEarlierRound() {
        String ana = fx.join(MDE, "Ana");
        Map<String, String> first = play(r1, Map.of(ana, 0.6));
        play(r2, Map.of(ana, 0.6));
        game.startPodium(MDE);
        fx.answerCommentary();
        game.nextPodium(MDE);
        Reveal.Revealed revealed = fx.state.cities.get(MDE).podium.revealed.getFirst();
        assertThat(revealed.attemptId).isEqualTo(first.get(ana));
        assertThat(revealed.roundId).isEqualTo(r1);
        assertThat(revealed.total).isEqualTo(120.0);
        Views.RevealEntry entry = game.views().stage(MDE).reveal().entries().getFirst();
        assertThat(entry.roundName()).isEqualTo("R1");
        assertThat(entry.commentary().es()).startsWith("ai-es final:");
    }

    @Test
    void hidingAnUnrevealedEntryRecomputesOnlyUnrevealedSteps() {
        Map<String, Double> cos = new LinkedHashMap<>();
        String[] tokens = new String[6];
        for (int i = 0; i < 6; i++) {
            tokens[i] = fx.join(MDE, "Pl" + i);
            cos.put(tokens[i], 0.1 * (i + 1)); // Pl5 best … Pl0 worst
        }
        Map<String, String> attempts = play(r1, cos);
        game.startPodium(MDE);
        fx.answerCommentary();
        game.nextPodium(MDE); // #5 = P1
        game.nextPodium(MDE); // #4 = P2
        Reveal podium = fx.state.cities.get(MDE).podium;
        assertThat(podium.revealed).extracting(r -> fx.state.cities.get(MDE).players.get(r.playerId).nickname)
                .containsExactly("Pl1", "Pl2");

        game.hideEntry(MDE, attempts.get(tokens[5]), true); // the leader is hidden before being revealed
        assertThat(fx.take(Effect.WriteCommentary.class)).as("P0 moves into the podium and gets a comment")
                .flatMap(Effect.WriteCommentary::jobs).extracting(Effect.CommentaryJob::key)
                .containsExactly("final:" + fx.playerId(tokens[0]));
        game.hideEntry(MDE, attempts.get(tokens[1]), true); // already revealed: its step never changes

        game.nextPodium(MDE);
        game.nextPodium(MDE);
        game.nextPodium(MDE);
        assertThat(podium.revealed).extracting(r -> fx.state.cities.get(MDE).players.get(r.playerId).nickname)
                .containsExactly("Pl1", "Pl2", "Pl0", "Pl3", "Pl4");
        assertThat(podium.revealed).extracting(r -> r.rank).containsExactly(5, 4, 3, 2, 1);
        assertThat(game.views().stage(MDE).reveal().entries()).as("hidden entries never reach the stage")
                .extracting(Views.RevealEntry::nickname).containsExactly("Pl2", "Pl0", "Pl3", "Pl4");
    }

    @Test
    void phonesSeeTheirFinalRankOnlyAfterNumberOneIsRevealed() {
        String ana = fx.join(MDE, "Ana");
        String beto = fx.join(MDE, "Beto");
        String none = fx.join(MDE, "Nobody");
        play(r1, Map.of(ana, 0.9, beto, 0.5));
        game.startPodium(MDE);
        fx.answerCommentary();
        game.nextPodium(MDE);
        assertThat(game.views().me(ana).finalResult()).isNull();
        game.nextPodium(MDE);
        assertThat(game.views().me(ana).finalResult()).isEqualTo(new Views.FinalResult(1, 90.0, true));
        assertThat(game.views().me(beto).finalResult()).isEqualTo(new Views.FinalResult(2, 50.0, true));
        assertThat(game.views().me(none).finalResult()).isEqualTo(new Views.FinalResult(null, 0.0, false));
    }

    @Test
    void miniPodiumRanksOneRoundAndRevealsThreeToOne() {
        String a = fx.join(MDE, "AAA");
        String b = fx.join(MDE, "BBB");
        String c = fx.join(MDE, "CCC");
        String d = fx.join(MDE, "DDD");
        play(r1, Map.of(a, 0.4, b, 0.9, c, 0.7, d, 0.1));
        game.startMiniPodium(MDE);
        game.nextMiniPodium(MDE);
        game.nextMiniPodium(MDE);
        game.nextMiniPodium(MDE);
        Views.StageReveal reveal = game.views().stage(MDE).reveal();
        assertThat(reveal.kind()).isEqualTo(RevealKind.MINI_PODIUM);
        assertThat(reveal.entries()).extracting(Views.RevealEntry::nickname).containsExactly("AAA", "CCC", "BBB");
        assertThat(reveal.entries()).extracting(Views.RevealEntry::total).containsOnlyNulls();
        assertThat(reveal.entries().getLast().roundScore()).isEqualTo(90.0);
    }
}
