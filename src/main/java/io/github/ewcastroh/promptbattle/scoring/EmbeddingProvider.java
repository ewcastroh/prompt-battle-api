package io.github.ewcastroh.promptbattle.scoring;

/** Image embedding port (SPEC §4). Throws {@link EmbeddingException} on any failure. */
public interface EmbeddingProvider {

    String providerId();

    String modelId();

    double[] embed(byte[] image, String mimeType);
}
