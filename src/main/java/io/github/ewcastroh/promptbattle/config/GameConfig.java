package io.github.ewcastroh.promptbattle.config;

import io.github.ewcastroh.promptbattle.admin.ProbeService;
import io.github.ewcastroh.promptbattle.city.AdminTokens;
import io.github.ewcastroh.promptbattle.city.CityCatalog;
import io.github.ewcastroh.promptbattle.city.GameRuntime;
import io.github.ewcastroh.promptbattle.commentary.CommentaryPort;
import io.github.ewcastroh.promptbattle.commentary.FallbackCommentary;
import io.github.ewcastroh.promptbattle.game.EventState;
import io.github.ewcastroh.promptbattle.game.Game;
import io.github.ewcastroh.promptbattle.game.GameEngine;
import io.github.ewcastroh.promptbattle.game.GameSettings;
import io.github.ewcastroh.promptbattle.game.Moderation;
import io.github.ewcastroh.promptbattle.generation.GenerationDispatcher;
import io.github.ewcastroh.promptbattle.generation.ImageGenerator;
import io.github.ewcastroh.promptbattle.generation.ProviderRegistry;
import io.github.ewcastroh.promptbattle.moderation.Blocklist;
import io.github.ewcastroh.promptbattle.moderation.PromptModerator;
import io.github.ewcastroh.promptbattle.moderation.PromptPreparation;
import io.github.ewcastroh.promptbattle.moderation.Translator;
import io.github.ewcastroh.promptbattle.persistence.ImageStore;
import io.github.ewcastroh.promptbattle.persistence.SnapshotCodec;
import io.github.ewcastroh.promptbattle.persistence.SnapshotStore;
import io.github.ewcastroh.promptbattle.scoring.EmbeddingProvider;
import io.github.ewcastroh.promptbattle.scoring.EmbeddingService;
import io.github.ewcastroh.promptbattle.scoring.RunScorer;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Wires the pure game domain to its adapters. */
@Configuration(proxyBeanMethods = false)
public class GameConfig {

    private static final Logger log = LoggerFactory.getLogger(GameConfig.class);

    @Bean
    CityCatalog cityCatalog(AppProperties props) {
        return new CityCatalog(props.cities());
    }

    @Bean
    AdminTokens adminTokens(Environment environment, CityCatalog catalog) {
        return new AdminTokens(environment, catalog);
    }

    @Bean
    SnapshotCodec snapshotCodec() {
        return new SnapshotCodec();
    }

    @Bean
    SnapshotStore snapshotStore(AppProperties props) {
        return new SnapshotStore(props.dataDir());
    }

    @Bean
    ImageStore imageStore(AppProperties props) {
        return new ImageStore(props.dataDir());
    }

    @Bean
    FallbackCommentary fallbackCommentary() {
        return new FallbackCommentary();
    }

    @Bean
    Moderation moderation() {
        return new Blocklist();
    }

    /**
     * The optional steps before generation. A flag without a model (no ANTHROPIC_API_KEY outside the fake
     * profile) is logged and ignored.
     */
    @Bean
    PromptPreparation promptPreparation(AppProperties props, ObjectProvider<PromptModerator> moderators,
            ObjectProvider<Translator> translators) {
        PromptModerator moderator = props.game().promptLlmModeration() ? moderators.getIfAvailable() : null;
        Translator translator = props.game().promptTranslateToEn() ? translators.getIfAvailable() : null;
        if (props.game().promptLlmModeration() && moderator == null) {
            log.warn("PROMPT_LLM_MODERATION is on but no commentary model is configured (ANTHROPIC_API_KEY); it is off");
        }
        if (props.game().promptTranslateToEn() && translator == null) {
            log.warn("PROMPT_TRANSLATE_TO_EN is on but no commentary model is configured (ANTHROPIC_API_KEY); it is off");
        }
        return new PromptPreparation(moderator, translator);
    }

    /** Image providers with an adapter (a key), their circuits and stats; the domain decides the active one. */
    @Bean
    ProviderRegistry providerRegistry(List<ImageGenerator> generators, AppProperties props, Clock clock) {
        return new ProviderRegistry(generators, props.image().providerChain(), props.image().loadSplit(), clock);
    }

    /** The shared FIFO and per-provider limits. Closed by {@link GameRuntime#stop()}. */
    @Bean(destroyMethod = "")
    GenerationDispatcher generationDispatcher(ProviderRegistry registry) {
        return new GenerationDispatcher(registry);
    }

    @Bean
    EmbeddingService embeddingService(List<EmbeddingProvider> providers, ImageStore images, AppProperties props,
            Clock clock) {
        return new EmbeddingService(providers, images, props.embedding().maxConcurrency(), clock);
    }

    @Bean
    RunScorer runScorer(EmbeddingService embeddings, GameSettings settings) {
        return new RunScorer(embeddings, settings.embeddingProviders());
    }

    /** Providers of the chain that have an adapter, in chain order. */
    static List<String> configured(List<String> chain, List<String> available) {
        List<String> result = new ArrayList<>();
        for (String id : chain) {
            String trimmed = id.strip();
            if (!trimmed.isEmpty() && available.contains(trimmed) && !result.contains(trimmed)) {
                result.add(trimmed);
            }
        }
        return result;
    }

    @Bean
    GameSettings gameSettings(AppProperties props, ProviderRegistry registry, EmbeddingService embeddings) {
        AppProperties.Game game = props.game();
        List<String> prompts = game.calibrationPrompts();
        int anchors = Math.max(0, Math.min(game.calibrationAnchors(), prompts.size()));
        List<String> images = registry.chain();
        List<String> embeddingIds = configured(props.embedding().providerChain(), embeddings.providerIds());
        if (images.isEmpty()) {
            log.warn("No image provider of IMAGE_PROVIDER_CHAIN has an adapter; rounds cannot start");
        }
        if (embeddingIds.isEmpty()) {
            log.warn("No embedding provider of EMBEDDING_PROVIDER_CHAIN has an adapter; scoring will fail");
        }
        return new GameSettings(game.roundGraceSeconds(), game.attemptsPerRound(), game.miniPodiumEnabled(),
                props.embedding().eager(), prompts.subList(0, anchors), images, embeddingIds, props.publicUiUrl());
    }

    /** The restored state if a snapshot exists, else a new one; configured cities are always present. */
    @Bean
    RestoredState restoredState(SnapshotStore store, SnapshotCodec codec, CityCatalog catalog) {
        Optional<byte[]> snapshot = store.load();
        EventState state = snapshot.map(codec::decode).orElseGet(EventState::new);
        state.syncCities(catalog.cities());
        if (snapshot.isPresent()) {
            log.info("Restored event state from {}", store.file());
        }
        return new RestoredState(state, snapshot.isPresent());
    }

    public record RestoredState(EventState state, boolean restored) {
    }

    @Bean
    Game game(RestoredState restored, GameSettings settings, Clock clock, Moderation moderation,
            FallbackCommentary fallback, GenerationDispatcher dispatcher, ProviderRegistry registry) {
        return new Game(restored.state(), settings, clock, moderation, fallback, dispatcher, registry,
                new SecureRandom());
    }

    @Bean
    GameRuntime gameRuntime(AppProperties props, GenerationDispatcher dispatcher, ImageStore images,
            EmbeddingService embeddings, RunScorer scorer, ObjectProvider<CommentaryPort> commentary,
            FallbackCommentary fallback, PromptPreparation preparation, SnapshotStore snapshots, SnapshotCodec codec,
            RestoredState restored) {
        return new GameRuntime(dispatcher, images, embeddings, scorer, commentary.getIfAvailable(() -> fallback),
                fallback, preparation, snapshots, codec, props.tickInterval(), props.snapshotInterval(),
                Duration.ofSeconds(props.image().timeoutSeconds()), props.commentary().timeout(), restored.restored());
    }

    @Bean
    ProbeService probeService(GameRuntime runtime, ProviderRegistry registry, EmbeddingService embeddings,
            ObjectProvider<CommentaryPort> commentary, FallbackCommentary fallback, AppProperties props,
            ImageStore images, GameEngine engine, Clock clock) {
        return new ProbeService(runtime, registry, embeddings, commentary.getIfAvailable(() -> fallback),
                props.commentary().model(), images, engine, clock);
    }

    @Bean(destroyMethod = "")
    GameEngine gameEngine(Game game, GameRuntime runtime) {
        GameEngine engine = new GameEngine(game, runtime);
        runtime.attach(engine);
        return engine;
    }
}
