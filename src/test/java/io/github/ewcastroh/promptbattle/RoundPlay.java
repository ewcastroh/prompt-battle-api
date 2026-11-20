package io.github.ewcastroh.promptbattle;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Plays a scored round on the fake profile: one entry per player with an exact score ({@code [sim=x]}). */
public final class RoundPlay {

    private RoundPlay() {
    }

    public record Player(String nickname, String token) {
    }

    public static List<Player> join(ApiClient api, String city, String prefix, int count) {
        List<Player> players = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            String nickname = String.format(Locale.ROOT, "%s_%02d", prefix, i);
            players.add(new Player(nickname, api.join(city, nickname).get("token").asString()));
        }
        return players;
    }

    public static String sim(double score, String words) {
        return String.format(Locale.ROOT, "[sim=%.3f] %s", score / 100.0, words);
    }

    /** Creates (and calibrates) a scored round from a {@code [target]} preview. */
    public static String scoredRound(ApiClient api, String city, String name) {
        return api.createRound(city, name, "[target] " + name, true).get("id").asString();
    }

    /**
     * Starts the round, gives {@code players.get(i)} the score {@code scores[i]} (null = no attempt), selects
     * each attempt, closes the round and waits for {@code ROUND_SCORED}. Returns each player's attempt id.
     */
    public static List<String> play(ApiClient api, String city, String roundId, List<Player> players,
            Double... scores) {
        api.adminPost(city, "/api/admin/rounds/" + roundId + "/start");
        List<String> attempts = new ArrayList<>();
        for (int i = 0; i < players.size(); i++) {
            Double score = i < scores.length ? scores[i] : null;
            attempts.add(score == null ? null : api.attempt(players.get(i).token(), sim(score, "entry " + i)));
        }
        api.awaitIdle(city);
        for (int i = 0; i < players.size(); i++) {
            if (attempts.get(i) != null) {
                api.select(players.get(i).token(), attempts.get(i));
            }
        }
        api.adminPost(city, "/api/admin/rounds/" + roundId + "/close");
        api.awaitPhase(city, "ROUND_SCORED");
        return attempts;
    }
}
