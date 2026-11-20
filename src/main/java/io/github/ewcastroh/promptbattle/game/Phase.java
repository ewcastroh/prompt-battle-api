package io.github.ewcastroh.promptbattle.game;

/** Per-city phase (API.md §4). */
public enum Phase {
    LOBBY,
    ROUND_OPEN,
    ROUND_CLOSING,
    ROUND_SCORED,
    MINI_PODIUM,
    FINAL_SCORING,
    PODIUM_REVEAL,
    FINISHED
}
