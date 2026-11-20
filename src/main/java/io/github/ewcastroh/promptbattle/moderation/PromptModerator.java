package io.github.ewcastroh.promptbattle.moderation;

/** Optional LLM prompt moderation (SPEC §6.3 step 3, {@code PROMPT_LLM_MODERATION}). */
public interface PromptModerator {

    /** False when the prompt must not be generated. Throws when no verdict could be obtained. */
    boolean allows(String prompt);
}
