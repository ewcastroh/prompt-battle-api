package io.github.ewcastroh.promptbattle.game;

import java.time.Instant;

final class Attempt {
    String id;
    String playerId;
    String roundId;
    String city;
    String prompt;
    /** English version used for generation (PROMPT_TRANSLATE_TO_EN, SPEC §5.6); null when not translated. */
    String promptEn;
    AttemptStatus status;
    /** Error code for BLOCKED or FAILED attempts. */
    ErrorCode code;
    FailureKind failureKind;
    String imageId;
    String provider;
    String model;
    Instant createdAt;
    Instant finishedAt;

    Attempt() {
    }
}
