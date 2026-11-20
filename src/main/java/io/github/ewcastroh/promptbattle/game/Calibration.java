package io.github.ewcastroh.promptbattle.game;

import java.util.ArrayList;
import java.util.List;

/** Calibration anchors of a round definition (SPEC §4.1). The floor itself is computed per city run. */
final class Calibration {
    CalibrationStatus status = CalibrationStatus.NOT_REQUIRED;
    /** Incremented on every restart, so stale results are ignored. */
    int version;
    String provider;
    List<Anchor> anchors = new ArrayList<>();

    Calibration() {
    }

    static final class Anchor {
        String prompt;
        String imageId;

        Anchor() {
        }

        Anchor(String prompt, String imageId) {
            this.prompt = prompt;
            this.imageId = imageId;
        }
    }
}
