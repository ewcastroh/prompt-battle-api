package io.github.ewcastroh.promptbattle.game;

import java.util.List;
import java.util.Map;

/** Results of asynchronous work, fed back into the engine as commands. */
public final class Outcomes {

    private Outcomes() {
    }

    public sealed interface AttemptOutcome {
    }

    public record Generated(String imageId, String provider, String model) implements AttemptOutcome {
    }

    public record Failed(FailureKind kind, String provider) implements AttemptOutcome {
    }

    /** The optional LLM moderation refused the prompt before generation (SPEC §6.3 step 3). */
    public record Moderated() implements AttemptOutcome {
    }

    public sealed interface CalibrationOutcome {
    }

    public record AnchorImage(String prompt, String imageId) {
    }

    public record Calibrated(String provider, List<AnchorImage> anchors) implements CalibrationOutcome {
    }

    public record CalibrationFailed(String reason) implements CalibrationOutcome {
    }

    public sealed interface ScoringOutcome {
    }

    /**
     * @param anchorCos cosine of each anchor to the target, in the order of the effect's anchor ids
     * @param entryCos  attempt id → cosine to the target
     */
    public record Scored(String embeddingProvider, List<Double> anchorCos, Map<String, Double> entryCos)
            implements ScoringOutcome {
    }

    public record ScoringFailed(String reason) implements ScoringOutcome {
    }
}
