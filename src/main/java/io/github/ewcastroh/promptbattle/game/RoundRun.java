package io.github.ewcastroh.promptbattle.game;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One city's run of one round definition. */
final class RoundRun {
    String roundId;
    String city;
    RunStatus status = RunStatus.NOT_STARTED;
    Instant startedAt;
    Instant endsAt;
    Instant closedAt;
    Instant graceEndsAt;
    int extensions;
    String imageProvider;
    String embeddingProvider;
    Double floor;
    /** Cosine of each calibration anchor to the target, in anchor order (set at scoring). */
    List<Double> anchorCos = new ArrayList<>();
    Map<String, Attempt> attempts = new LinkedHashMap<>();
    /** Player id → entry. */
    Map<String, Entry> entries = new LinkedHashMap<>();
    /** Player id → moderation blocks in this run. */
    Map<String, Integer> blocks = new LinkedHashMap<>();

    RoundRun() {
    }

    RoundRun(String roundId, String city) {
        this.roundId = roundId;
        this.city = city;
    }

    int inFlight() {
        int n = 0;
        for (Attempt a : attempts.values()) {
            if (a.status.inFlight()) {
                n++;
            }
        }
        return n;
    }

    List<Attempt> attemptsOf(String playerId) {
        List<Attempt> result = new ArrayList<>();
        for (Attempt a : attempts.values()) {
            if (a.playerId.equals(playerId)) {
                result.add(a);
            }
        }
        return result;
    }

    int doneCount(String playerId) {
        int n = 0;
        for (Attempt a : attempts.values()) {
            if (a.playerId.equals(playerId) && a.status == AttemptStatus.DONE) {
                n++;
            }
        }
        return n;
    }
}
