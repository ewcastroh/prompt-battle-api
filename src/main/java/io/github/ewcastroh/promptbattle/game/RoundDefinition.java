package io.github.ewcastroh.promptbattle.game;

/** A shared round definition, played once per city. */
final class RoundDefinition {
    String id;
    int position;
    String name;
    boolean scored;
    String hiddenPrompt;
    int durationSec;
    int attemptsPerPlayer;
    long seed;
    String targetImageId;
    TargetSource targetSource;
    boolean locked;
    Calibration calibration = new Calibration();

    RoundDefinition() {
    }
}
