package io.github.ewcastroh.promptbattle.game;

import java.util.Map;
import java.util.regex.Pattern;

/** Prompt normalization (SPEC §3.3): trimmed, whitespace collapsed, 1–max chars. */
public final class PromptRules {

    public static final int PLAYER_MAX = 300;
    public static final int ADMIN_MAX = 1000;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private PromptRules() {
    }

    public static String normalize(String raw, int max) {
        String prompt = raw == null ? "" : WHITESPACE.matcher(raw.strip()).replaceAll(" ");
        int length = prompt.codePointCount(0, prompt.length());
        if (length < 1 || length > max) {
            throw new GameException(ErrorCode.PROMPT_INVALID, "Prompt length " + length,
                    Map.of("min", 1, "max", max));
        }
        return prompt;
    }
}
