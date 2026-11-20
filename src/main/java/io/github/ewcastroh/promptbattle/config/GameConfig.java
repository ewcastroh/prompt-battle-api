package io.github.ewcastroh.promptbattle.config;

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
import io.github.ewcastroh.promptbattle.generation.GenerationQueue;
import io.github.ewcastroh.promptbattle.generation.ImageGenerator;
import io.github.ewcastroh.promptbattle.moderation.BlocklistModeration;
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
        return new BlocklistModeration();
    }

    @Bean(destroyMethod = "")
    GenerationQueue generationQueue(List<ImageGenerator> generators) {
        return new GenerationQueue(generators);
    }

    @Bean
    EmbeddingService embeddingService(List<EmbeddingProvider> providers, ImageStore images) {
        return new EmbeddingService(providers, images);
    }

    @Bean
    RunScorer runScorer(EmbeddingService embeddings) {
        return new RunScorer(embeddings);
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
    GameSettings gameSettings(AppProperties props, GenerationQueue queue, EmbeddingService embeddings) {
        AppProperties.Game game = props.game();
        List<String> prompts = game.calibrationPrompts();
        int anchors = Math.max(0, Math.min(game.calibrationAnchors(), prompts.size()));
        List<String> images = configured(props.image().providerChain(), List.copyOf(queue.generators().keySet()));
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
            FallbackCommentary fallback, GenerationQueue queue) {
        return new Game(restored.state(), settings, clock, moderation, fallback, queue, new SecureRandom());
    }

    @Bean
    GameRuntime gameRuntime(AppProperties props, GenerationQueue queue, ImageStore images,
            EmbeddingService embeddings, RunScorer scorer, ObjectProvider<CommentaryPort> commentary,
            FallbackCommentary fallback, SnapshotStore snapshots, SnapshotCodec codec, RestoredState restored) {
        return new GameRuntime(queue, images, embeddings, scorer, commentary.getIfAvailable(() -> fallback),
                fallback, snapshots, codec, props.tickInterval(), props.snapshotInterval(),
                Duration.ofSeconds(props.image().timeoutSeconds()), restored.restored());
    }

    @Bean(destroyMethod = "")
    GameEngine gameEngine(Game game, GameRuntime runtime) {
        GameEngine engine = new GameEngine(game, runtime);
        runtime.attach(engine);
        return engine;
    }
}
