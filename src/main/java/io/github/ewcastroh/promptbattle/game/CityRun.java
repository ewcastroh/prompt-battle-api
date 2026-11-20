package io.github.ewcastroh.promptbattle.game;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Everything that belongs to one city: players, runs, phase, reveals and commentary. */
final class CityRun {
    String code;
    String name;
    Phase phase = Phase.LOBBY;
    /** The city's current or last started round. */
    String currentRoundId;
    Map<String, Player> players = new LinkedHashMap<>();
    /** SHA-256 hex of a player token → player id. */
    Map<String, String> tokens = new LinkedHashMap<>();
    Map<String, RoundRun> runs = new LinkedHashMap<>();
    Reveal miniPodium;
    Reveal podium;
    /** Attempt ids shown in the practice gallery, or null when hidden. */
    List<String> practiceGallery;
    Map<String, Commentary> commentary = new LinkedHashMap<>();
    LinkedHashSet<String> commentaryRequested = new LinkedHashSet<>();

    CityRun() {
    }

    CityRun(String code, String name) {
        this.code = code;
        this.name = name;
    }

    RoundRun currentRun() {
        return currentRoundId == null ? null : runs.get(currentRoundId);
    }

    List<Player> activePlayers() {
        List<Player> result = new ArrayList<>();
        for (Player p : players.values()) {
            if (!p.kicked) {
                result.add(p);
            }
        }
        return result;
    }
}
