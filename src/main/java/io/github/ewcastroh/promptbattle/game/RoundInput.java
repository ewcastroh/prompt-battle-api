package io.github.ewcastroh.promptbattle.game;

/**
 * Fields of a round definition from the admin (API.md §7). On update, null means "keep".
 */
public record RoundInput(String name, String hiddenPrompt, Integer durationSec, Integer attemptsPerPlayer,
        Boolean scored, Long seed) {

    public static final int NAME_MAX = 60;
    public static final int HIDDEN_PROMPT_MAX = 1000;
    public static final long SEED_MAX = 2147483647L;

    /** Throws {@code VALIDATION_FAILED {field}} for the first invalid field. */
    public void validate(boolean create) {
        if (create || name != null) {
            requireLength("name", name, NAME_MAX);
        }
        if (create || hiddenPrompt != null) {
            requireLength("hiddenPrompt", hiddenPrompt, HIDDEN_PROMPT_MAX);
        }
        if (durationSec != null && (durationSec < 60 || durationSec > 1800)) {
            throw GameException.validation("durationSec");
        }
        if (attemptsPerPlayer != null && (attemptsPerPlayer < 1 || attemptsPerPlayer > 10)) {
            throw GameException.validation("attemptsPerPlayer");
        }
        if (seed != null && (seed < 0 || seed > SEED_MAX)) {
            throw GameException.validation("seed");
        }
    }

    private static void requireLength(String field, String value, int max) {
        if (value == null) {
            throw GameException.validation(field);
        }
        String trimmed = value.strip();
        int length = trimmed.codePointCount(0, trimmed.length());
        if (length < 1 || length > max) {
            throw GameException.validation(field);
        }
    }
}
