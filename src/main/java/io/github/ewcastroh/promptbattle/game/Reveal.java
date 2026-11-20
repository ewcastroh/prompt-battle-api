package io.github.ewcastroh.promptbattle.game;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** A mini-podium or the final podium of one city. Step 0 is the intro; step k reveals rank size − k + 1. */
final class Reveal {
    RevealKind kind;
    String roundId;
    int size;
    int step;
    Instant startedAt;
    List<Revealed> revealed = new ArrayList<>();

    Reveal() {
    }

    boolean isRevealed(String playerId) {
        for (Revealed r : revealed) {
            if (r.playerId.equals(playerId)) {
                return true;
            }
        }
        return false;
    }

    /** A revealed step. Frozen: never changes after the reveal. */
    static final class Revealed {
        int rank;
        String playerId;
        String attemptId;
        String roundId;
        double roundScore;
        Double total;
        String commentaryKey;

        Revealed() {
        }
    }
}
