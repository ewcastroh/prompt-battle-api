package io.github.ewcastroh.promptbattle.game;

/** Where a round's target image comes from. */
public sealed interface TargetChoice {

    record FromPreview(String previewId) implements TargetChoice {
    }

    /** An uploaded image already stored by the image store. */
    record Uploaded(String imageId) implements TargetChoice {
    }
}
