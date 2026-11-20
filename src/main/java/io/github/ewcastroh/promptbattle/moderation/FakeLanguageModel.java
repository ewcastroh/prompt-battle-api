package io.github.ewcastroh.promptbattle.moderation;

import java.util.Locale;

/**
 * Profile {@code fake}: offline stand-ins for LLM moderation and translation, so both flags can be tried
 * without a key. A prompt containing {@code [llm-block]} is refused; {@code [llm-fail]} makes the call fail.
 * The translation is the prompt prefixed with {@code "EN: "} (markers like {@code [sim=0.8]} survive).
 */
public final class FakeLanguageModel implements PromptModerator, Translator {

    @Override
    public boolean allows(String prompt) {
        String p = prompt.toLowerCase(Locale.ROOT);
        if (p.contains("[llm-fail]")) {
            throw new IllegalStateException("fake moderation failure");
        }
        return !p.contains("[llm-block]");
    }

    @Override
    public String toEnglish(String prompt) {
        if (prompt.toLowerCase(Locale.ROOT).contains("[llm-fail]")) {
            throw new IllegalStateException("fake translation failure");
        }
        return "EN: " + prompt;
    }
}
