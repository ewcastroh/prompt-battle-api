package io.github.ewcastroh.promptbattle.game;

/** Status of one city's run of one round definition (API.md §4). */
public enum RunStatus {
    NOT_STARTED,
    OPEN,
    CLOSING,
    SCORING,
    SCORED,
    SCORING_FAILED,
    /** Practice run finished. */
    CLOSED
}
