package io.github.ewcastroh.promptbattle.game;

import java.util.List;

/**
 * Game configuration the domain needs.
 *
 * @param imageProviders     configured image providers in failover order (the first is active at start)
 * @param embeddingProviders configured embedding providers in failover order
 */
public record GameSettings(
        int graceSeconds,
        int defaultAttempts,
        boolean miniPodiumEnabled,
        boolean eagerEmbeddings,
        List<String> calibrationPrompts,
        List<String> imageProviders,
        List<String> embeddingProviders,
        String publicUiUrl) {

    public GameSettings {
        calibrationPrompts = List.copyOf(calibrationPrompts);
        imageProviders = List.copyOf(imageProviders);
        embeddingProviders = List.copyOf(embeddingProviders);
        publicUiUrl = publicUiUrl.endsWith("/") ? publicUiUrl.substring(0, publicUiUrl.length() - 1) : publicUiUrl;
    }
}
