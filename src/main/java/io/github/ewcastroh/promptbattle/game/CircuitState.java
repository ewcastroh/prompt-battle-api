package io.github.ewcastroh.promptbattle.game;

/** State of an image provider's circuit breaker (SPEC §5.4, API.md {@code circuit}). */
public enum CircuitState {
    CLOSED,
    OPEN,
    /** The open period is over; one trial call decides between CLOSED and OPEN. */
    HALF_OPEN
}
