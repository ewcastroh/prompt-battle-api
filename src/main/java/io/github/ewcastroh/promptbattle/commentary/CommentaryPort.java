package io.github.ewcastroh.promptbattle.commentary;

import io.github.ewcastroh.promptbattle.game.Commentary;
import io.github.ewcastroh.promptbattle.game.Effect;

/** Podium commentary in both languages (SPEC §4.4). A3 adds the Spring AI implementation. */
public interface CommentaryPort {
    Commentary comment(Effect.CommentaryJob job);
}
