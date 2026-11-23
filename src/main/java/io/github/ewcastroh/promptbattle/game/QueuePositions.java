package io.github.ewcastroh.promptbattle.game;

/** Read-only view of the shared generation FIFO. */
@FunctionalInterface
public interface QueuePositions {
    /** 1-based position of a queued attempt, or null when it is not waiting in the queue. */
    Integer positionOf(String attemptId);

    /**
     * True once the dispatcher took the job out of the FIFO for a provider, until the domain applied its result.
     * The view reports such a QUEUED attempt as GENERATING (the domain hasn't heard of the start yet), so a
     * QUEUED attempt in the FIFO always has a position. Exception: with the LLM moderation or translation flag
     * on, an attempt is QUEUED without a position while its prompt is prepared, before it enters the FIFO.
     */
    default boolean started(String attemptId) {
        return false;
    }

    QueuePositions NONE = attemptId -> null;
}
