package io.github.ewcastroh.promptbattle.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Every SPEC §11.1 variable (mapped from env vars in application.yml). Model IDs live only here and in
 * the environment.
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String publicUiUrl,
        List<String> corsAllowedOrigins,
        String cities,
        Path dataDir,
        Duration snapshotInterval,
        Duration tickInterval,
        Image image,
        Provider fal,
        Provider bfl,
        Embedding embedding,
        Gemini gemini,
        Cohere cohere,
        Commentary commentary,
        Game game,
        Fake fake) {

    public record Image(List<String> providerChain, List<String> loadSplit, int timeoutSeconds) {
    }

    public record Provider(String key, String model, int maxConcurrency) {
    }

    /** {@code mode}: {@code entries} (D-013, default) or {@code eager}. */
    public record Embedding(List<String> providerChain, String mode) {

        public boolean eager() {
            return "eager".equalsIgnoreCase(mode);
        }
    }

    public record Gemini(String apiKey, String embeddingModel, int embeddingDimensions, int embeddingRpm,
            int embeddingTpm, int embeddingRpd) {
    }

    public record Cohere(String apiKey, String embeddingModel) {
    }

    public record Commentary(String apiKey, String model) {
    }

    public record Game(int attemptsPerRound, int roundGraceSeconds, int calibrationAnchors,
            List<String> calibrationPrompts, boolean miniPodiumEnabled, boolean promptTranslateToEn,
            boolean promptLlmModeration) {
    }

    /** The {@code fake} providers (profile {@code fake}). */
    public record Fake(String model, long latencyMs, double failureRate, int maxConcurrency,
            int embeddingDimensions) {
    }

    /** Configured model IDs by property name, for the forbidden-models guard. */
    public java.util.Map<String, String> modelIds() {
        java.util.Map<String, String> ids = new java.util.LinkedHashMap<>();
        ids.put("FAL_MODEL", fal.model());
        ids.put("BFL_MODEL", bfl.model());
        ids.put("GEMINI_EMBEDDING_MODEL", gemini.embeddingModel());
        ids.put("COHERE_EMBEDDING_MODEL", cohere.embeddingModel());
        ids.put("COMMENTARY_MODEL", commentary.model());
        return ids;
    }
}
