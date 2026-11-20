package io.github.ewcastroh.promptbattle.generation;

/** Image generation port (SPEC §5.1). Adapters own their HTTP timeouts. */
public interface ImageGenerator {

    String providerId();

    String modelId();

    /** Concurrent calls this provider allows (its executor limit, SPEC §5.5). */
    int maxConcurrency();

    GenerationResult generate(GenerationRequest request);
}
