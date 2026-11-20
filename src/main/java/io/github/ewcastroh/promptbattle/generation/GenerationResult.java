package io.github.ewcastroh.promptbattle.generation;

import io.github.ewcastroh.promptbattle.game.FailureKind;

public sealed interface GenerationResult {

    record Success(byte[] bytes, String mimeType, String modelId, long latencyMs) implements GenerationResult {
    }

    record Failure(FailureKind kind, String message) implements GenerationResult {
    }
}
