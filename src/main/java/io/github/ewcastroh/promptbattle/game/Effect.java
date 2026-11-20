package io.github.ewcastroh.promptbattle.game;

import java.util.List;
import java.util.Map;

/**
 * Side effects requested by a command. The engine hands them to an {@link EffectSink} after the command,
 * on the engine thread; the sink must only enqueue work and never block.
 */
public sealed interface Effect {

    record GenerateAttempt(String attemptId, String provider, String prompt, long seed) implements Effect {
    }

    record CancelGeneration(String attemptId) implements Effect {
    }

    record Calibrate(String roundId, int version, String provider, long seed, List<String> prompts)
            implements Effect {
    }

    /** Embed the target, anchors and entries of one city run with one provider. */
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

    /** Persist the state now (phase transitions). */
    record Snapshot() implements Effect {
    }

    /** Event reset: drop queued work, delete every image and the snapshot. */
    record ResetStorage() implements Effect {
    }
}
