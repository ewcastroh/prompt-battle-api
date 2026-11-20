package io.github.ewcastroh.promptbattle.game;

/** Deterministic content checks (SPEC §6.3). Implemented outside the domain. */
public interface Moderation {
    boolean blocksNickname(String nickname);

    boolean blocksPrompt(String prompt);

    Moderation NONE = new Moderation() {
        @Override
        public boolean blocksNickname(String nickname) {
            return false;
        }

        @Override
        public boolean blocksPrompt(String prompt) {
            return false;
        }
    };
}
