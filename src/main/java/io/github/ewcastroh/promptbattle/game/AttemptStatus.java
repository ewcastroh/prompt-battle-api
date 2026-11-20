package io.github.ewcastroh.promptbattle.game;

public enum AttemptStatus {
    QUEUED,
    GENERATING,
    DONE,
    BLOCKED,
    FAILED;

    public boolean inFlight() {
        return this == QUEUED || this == GENERATING;
    }
}
