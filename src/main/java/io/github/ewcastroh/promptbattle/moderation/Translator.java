package io.github.ewcastroh.promptbattle.moderation;

/** Optional prompt translation to English before generation (SPEC §5.6, {@code PROMPT_TRANSLATE_TO_EN}). */
public interface Translator {

    /** The prompt in English. Throws when no usable translation could be obtained. */
    String toEnglish(String prompt);
}
