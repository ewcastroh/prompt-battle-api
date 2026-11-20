package io.github.ewcastroh.promptbattle.game;

/** Read-only view of the shared generation FIFO. */
@FunctionalInterface
public interface QueuePositions {
    /** 1-based position of a queued attempt, or null when it is not waiting in the queue. */
    Integer positionOf(String attemptId);

    QueuePositions NONE = attemptId -> null;
}
