package io.github.ewcastroh.promptbattle.game;

import java.util.List;

/** Receives the effects of each command on the engine thread. Must not block. */
@FunctionalInterface
public interface EffectSink {
    void accept(EventState state, List<Effect> effects);
}
