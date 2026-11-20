package io.github.ewcastroh.promptbattle.moderation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The optional steps between a player's attempt and its generation: LLM moderation (SPEC §6.3 step 3), then
 * translation to English (SPEC §5.6). Each runs only when its flag is on and a model is configured. Runs on a
 * worker thread, never on the engine thread.
 * <p>Failures don't stop the game: if the moderation call fails the prompt is allowed (the blocklist already
 * ran, and the image provider's safety checker still applies); if the translation fails, the original prompt
 * is generated. Prompt text is never logged above DEBUG.
 */
public final class PromptPreparation {

    private static final Logger log = LoggerFactory.getLogger(PromptPreparation.class);

    /** Nothing to do before generation. */
    public static final PromptPreparation NONE = new PromptPreparation(null, null);

    /**
     * @param blocked          the moderation refused the prompt
     * @param generationPrompt the prompt to generate (the translation when there is one)
     * @param promptEn         the stored English version, or null
     */
    public record Prepared(boolean blocked, String generationPrompt, String promptEn) {
    }

    private final PromptModerator moderator;
    private final Translator translator;

    /** Either may be null (that step is off). */
    public PromptPreparation(PromptModerator moderator, Translator translator) {
        this.moderator = moderator;
        this.translator = translator;
    }

    public boolean active() {
        return moderator != null || translator != null;
    }

    public boolean moderates() {
        return moderator != null;
    }

    public boolean translates() {
        return translator != null;
    }

    public Prepared prepare(String prompt) {
        if (moderator != null) {
            try {
                if (!moderator.allows(prompt)) {
                    log.debug("LLM moderation blocked prompt: {}", prompt);
                    return new Prepared(true, prompt, null);
                }
            } catch (RuntimeException e) {
                log.warn("LLM moderation failed ({}); the prompt is allowed", e.getClass().getSimpleName());
            }
        }
        if (translator != null) {
            try {
                String en = translator.toEnglish(prompt);
                log.debug("Translated prompt: {} -> {}", prompt, en);
                return new Prepared(false, en, en);
            } catch (RuntimeException e) {
                log.warn("Prompt translation failed ({}); generating the original prompt", e.getClass().getSimpleName());
            }
        }
        return new Prepared(false, prompt, null);
    }
}
