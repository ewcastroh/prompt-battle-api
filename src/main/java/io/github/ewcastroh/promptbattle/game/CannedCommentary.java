package io.github.ewcastroh.promptbattle.game;

/** Canned comment by score band, used when an AI comment is not ready (SPEC §4.4). */
@FunctionalInterface
public interface CannedCommentary {
    Commentary canned(String key, double score);
}
