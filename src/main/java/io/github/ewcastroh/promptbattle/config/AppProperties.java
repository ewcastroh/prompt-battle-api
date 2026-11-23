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
        Fal fal,
        Bfl bfl,
        Embedding embedding,
        Gemini gemini,
        Cohere cohere,
        Commentary commentary,
        Game game,
        Fake fake) {

    public record Image(List<String> providerChain, List<String> loadSplit, int timeoutSeconds) {
    }

    /** fal.ai queue API (SPEC §5.2). {@code queueUrl} is the queue host; the model id is its path. */
    public record Fal(String key, String model, int maxConcurrency, String queueUrl, Duration pollInterval) {
    }

    /** Black Forest Labs direct API; the model id is the endpoint path under {@code baseUrl/v1}. */
    public record Bfl(String key, String model, int maxConcurrency, String baseUrl, int safetyTolerance,
            Duration pollInterval) {
    }

    /**
     * {@code mode}: {@code entries} (D-013, default) or {@code eager}. {@code maxConcurrency}: embedding calls
     * in flight per provider; {@code timeoutSeconds}: budget of one embedding call including its retry.
     */
    public record Embedding(List<String> providerChain, String mode, int maxConcurrency, int timeoutSeconds) {

        public boolean eager() {
            return "eager".equalsIgnoreCase(mode);
        }
    }

    /** {@code estimatedTokens}: reserved per image in the TPM window until usageMetadata reports the real count. */
    public record Gemini(String apiKey, String embeddingModel, int embeddingDimensions, int embeddingRpm,
            int embeddingTpm, int embeddingRpd, int estimatedTokens, String baseUrl) {
    }

    public record Cohere(String apiKey, String embeddingModel, int embeddingDimensions, String baseUrl) {
    }

    /**
     * The commentary model, also used by the optional LLM moderation and translation. {@code timeout} is the
     * per-call budget (SPEC §4.4: 8 s); {@code baseUrl} only changes in tests.
     */
    public record Commentary(String apiKey, String model, String baseUrl, Duration timeout) {
    }

    public record Game(int attemptsPerRound, int roundGraceSeconds, int calibrationAnchors,
            List<String> calibrationPrompts, boolean miniPodiumEnabled, boolean promptTranslateToEn,
            boolean promptLlmModeration) {
    }

    /**
     * The {@code fake} providers (profile {@code fake}). {@code secondMaxConcurrency} is the slot limit of the
     * second fake image provider ({@code fake2}), which stands in for BFL in load-split runs (PLAN I2).
     */
    public record Fake(String model, long latencyMs, double failureRate, int maxConcurrency,
            int secondMaxConcurrency, int embeddingDimensions) {
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
