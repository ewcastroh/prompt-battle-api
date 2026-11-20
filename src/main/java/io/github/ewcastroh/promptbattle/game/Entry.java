package io.github.ewcastroh.promptbattle.game;

import java.time.Instant;

/** The attempt a player selected (or got automatically) for a round run. */
final class Entry {
    String attemptId;
    String playerId;
    Instant selectedAt;
    boolean auto;
    boolean hidden;
    Double cos;
    Double score;

    Entry() {
    }
}
