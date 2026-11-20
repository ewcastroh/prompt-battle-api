package io.github.ewcastroh.promptbattle.game;

/** Why an image generation failed (SPEC §5.1). */
public enum FailureKind {
    SAFETY_BLOCKED,
    RATE_LIMITED,
    TIMEOUT,
    PROVIDER_ERROR,
    INVALID_RESPONSE
}
