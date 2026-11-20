package io.github.ewcastroh.promptbattle.game;

import java.time.Instant;

final class Attempt {
    String id;
    String playerId;
    String roundId;
    String city;
    String prompt;
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
