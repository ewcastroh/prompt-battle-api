package io.github.ewcastroh.promptbattle.game;

import java.util.List;
import java.util.Map;

/**
 * Side effects requested by a command. The engine hands them to an {@link EffectSink} after the command,
 * on the engine thread; the sink must only enqueue work and never block.
 */
public sealed interface Effect {

    /**
     * Queue a player's generation in the shared FIFO. The dispatcher picks the provider when the job starts
     * (active provider first, then the load split's overflow) and reports it back.
     */
    record GenerateAttempt(String attemptId, String prompt, long seed) implements Effect {
    }

    record CancelGeneration(String attemptId) implements Effect {
    }

    /** Generate the anchors of a round definition, all on {@code provider}. */
    record Calibrate(String roundId, int version, String provider, long seed, List<String> prompts)
            implements Effect {
    }

    /**
     * Embed the target, anchors and entries of one city run, starting with {@code embeddingProvider} and
     * failing over along the embedding chain; every vector of the run comes from one provider (SPEC §4.3).
     */
    record ScoreRun(String city, String roundId, String embeddingProvider, String targetImageId,
            List<String> anchorImageIds, Map<String, String> entryImageIds) implements Effect {
    }

    record WriteCommentary(String city, List<CommentaryJob> jobs) implements Effect {
    }

    record CommentaryJob(String key, String targetImageId, String entryImageId, double score, int rank,
            Double total) {
    }

    record PrefetchEmbedding(String imageId, String provider) implements Effect {
    }

    /**
     * The event-wide active image provider changed (failover, force or restore): the dispatcher prefers it
     * from now on. {@code resetCircuit} closes its circuit (an admin's forced switch).
     */
    record ActiveImageProvider(String providerId, boolean resetCircuit) implements Effect {
    }

    /** Scoring with this embedding provider averages two calls per image (SPEC §4.2), or stops doing so. */
    record EmbeddingAveraging(String providerId, boolean averaging) implements Effect {
    }

    /** Persist the state now (phase transitions). */
    record Snapshot() implements Effect {
    }

    /** Event reset: drop queued work, delete every image and the snapshot. */
    record ResetStorage() implements Effect {
    }
}
