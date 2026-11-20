package io.github.ewcastroh.promptbattle.game;

/** Machine-readable error codes (API.md §2) with their HTTP status. Codes are never removed. */
public enum ErrorCode {
    NICKNAME_INVALID(400),
    NICKNAME_TAKEN(409),
    NICKNAME_BLOCKED(422),
    CITY_NOT_FOUND(404),
    UNAUTHORIZED(401),
    PLAYER_KICKED(403),
    ROUND_NOT_OPEN(409),
    GENERATION_IN_FLIGHT(409),
    NO_ATTEMPTS_LEFT(409),
    PROMPT_INVALID(400),
    PROMPT_BLOCKED(422),
    ATTEMPT_NOT_FOUND(404),
    ATTEMPT_NOT_SELECTABLE(409),
    GENERATION_FAILED(502),
    INVALID_TRANSITION(409),
    ROUND_LOCKED(409),
    RATE_LIMITED(429),
    ADMIN_FORBIDDEN(403),
    RESET_NOT_CONFIRMED(400),
    INTERNAL_ERROR(500),
    ROUND_NOT_FOUND(404),
    PLAYER_NOT_FOUND(404),
    ENTRY_NOT_FOUND(404),
    PREVIEW_NOT_FOUND(404),
    PROVIDER_NOT_FOUND(404),
    PROVIDER_UNAVAILABLE(503),
    ROUND_NOT_READY(409),
    UPLOAD_INVALID(400),
    VALIDATION_FAILED(400),
    SCORING_FAILED(409),
    NOT_FOUND(404);

    private final int httpStatus;

    ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
