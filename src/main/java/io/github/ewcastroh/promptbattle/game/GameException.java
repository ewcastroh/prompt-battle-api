package io.github.ewcastroh.promptbattle.game;

import java.util.Map;

/** A rule violation with a machine-readable code. {@code detail} is for logs only, never for players. */
public final class GameException extends RuntimeException {

    private final ErrorCode code;
    private final Map<String, Object> params;

    public GameException(ErrorCode code, String detail) {
        this(code, detail, Map.of());
    }

    public GameException(ErrorCode code, String detail, Map<String, Object> params) {
        super(detail, null, false, false);
        this.code = code;
        this.params = Map.copyOf(params);
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, Object> params() {
        return params;
    }

    public static GameException invalidTransition(Phase phase, String action) {
        return new GameException(ErrorCode.INVALID_TRANSITION, action + " not allowed in phase " + phase,
                Map.of("phase", phase.name()));
    }

    public static GameException validation(String field) {
        return new GameException(ErrorCode.VALIDATION_FAILED, "Invalid field " + field, Map.of("field", field));
    }
}
