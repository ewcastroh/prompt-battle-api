package io.github.ewcastroh.promptbattle.admin;

import io.github.ewcastroh.promptbattle.commentary.CommentaryPort;
import io.github.ewcastroh.promptbattle.config.AppProperties;
import io.github.ewcastroh.promptbattle.game.CircuitState;
import io.github.ewcastroh.promptbattle.game.FailureKind;
import io.github.ewcastroh.promptbattle.game.GameEngine;
import io.github.ewcastroh.promptbattle.game.GameException;
import io.github.ewcastroh.promptbattle.game.GameViews;
import io.github.ewcastroh.promptbattle.game.Views;
import io.github.ewcastroh.promptbattle.generation.FakeImageGenerator;
import io.github.ewcastroh.promptbattle.generation.GenerationDispatcher;
import io.github.ewcastroh.promptbattle.generation.ProviderRegistry;
import io.github.ewcastroh.promptbattle.scoring.EmbeddingProvider;
import io.github.ewcastroh.promptbattle.scoring.EmbeddingService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Shared provider controls (API.md §7): status, forced switch (alert to every admin) and the probe. */
@RestController
class ProviderController {

    record ForceRequest(String providerId) {
    }

    record LastError(FailureKind kind, Instant at) {
    }

    record ImageProvider(String id, String model, boolean configured, CircuitState circuit, int inFlight,
            int maxConcurrency, Long p50Ms, Long p95Ms, long successCount, long failureCount, LastError lastError) {
    }

    record Determinism(Double maxDiff, boolean averaging, Instant checkedAt) {
    }

    record EmbeddingProviderStatus(String id, String model, boolean configured, boolean healthy,
            Determinism determinism) {
    }

    record CommentaryStatus(String model, boolean configured) {
    }

    record Providers(String active, List<String> chain, List<String> loadSplit, int queueLength,
            List<ImageProvider> image, List<EmbeddingProviderStatus> embedding, CommentaryStatus commentary) {
    }

    private final GameEngine engine;
    private final GenerationDispatcher dispatcher;
    private final ProviderRegistry registry;
    private final EmbeddingService embeddings;
    private final CommentaryPort commentary;
    private final ProbeService probe;
    private final AppProperties props;

    ProviderController(GameEngine engine, GenerationDispatcher dispatcher, EmbeddingService embeddings,
            ObjectProvider<CommentaryPort> commentary, ProbeService probe, AppProperties props) {
        this.engine = engine;
        this.dispatcher = dispatcher;
        this.registry = dispatcher.registry();
        this.embeddings = embeddings;
        this.commentary = commentary.getIfAvailable();
        this.probe = probe;
        this.props = props;
    }

    @GetMapping("/api/admin/providers")
    Providers providers() {
        return view();
    }

    @PostMapping("/api/admin/providers/active")
    Providers force(@RequestAttribute(AdminAuthInterceptor.CITY) String city,
            @RequestBody(required = false) ForceRequest body) {
        String providerId = body == null ? null : body.providerId();
        if (providerId == null || providerId.isBlank()) {
            throw GameException.validation("providerId");
        }
        engine.run(g -> g.forceImageProvider(providerId.strip(), city));
        return view();
    }

    /** Synchronous, up to about a minute; spends real credits. */
    @PostMapping("/api/admin/providers/probe")
    ProbeService.Report probe() {
        return probe.run();
    }

    private Providers view() {
        Views.ProviderState state = engine.read(GameViews::providerState);
        List<String> chain = ids(props.image().providerChain());
        List<String> split = ids(props.image().loadSplit());
        List<String> imageIds = new ArrayList<>(chain);
        split.forEach(id -> {
            if (!imageIds.contains(id)) {
                imageIds.add(id);
            }
        });
        List<ImageProvider> image = new ArrayList<>();
        for (String id : imageIds) {
            ProviderRegistry.Stats s = registry.stats(id);
            if (s == null) {
                image.add(new ImageProvider(id, configuredModel(id), false, null, 0, 0, null, null, 0, 0, null));
            } else {
                image.add(new ImageProvider(id, s.model(), true, s.circuit(), dispatcher.inFlight(id),
                        dispatcher.maxConcurrency(id), s.p50Ms(), s.p95Ms(), s.successCount(), s.failureCount(),
                        s.lastErrorKind() == null ? null : new LastError(s.lastErrorKind(), s.lastErrorAt())));
            }
        }
        List<EmbeddingProviderStatus> embedding = new ArrayList<>();
        for (String id : ids(props.embedding().providerChain())) {
            Views.EmbeddingProviderView saved = state.embedding().get(id);
            Determinism determinism = saved == null ? new Determinism(null, false, null)
                    : new Determinism(saved.maxDiff(), saved.averaging(), saved.checkedAt());
            EmbeddingProvider provider = embeddings.provider(id);
            if (provider == null) {
                embedding.add(new EmbeddingProviderStatus(id, configuredModel(id), false, false, determinism));
                continue;
            }
            EmbeddingService.Health h = embeddings.health(id);
            boolean failedLast = h != null && h.lastFailureAt() != null
                    && (h.lastSuccessAt() == null || h.lastFailureAt().isAfter(h.lastSuccessAt()));
            embedding.add(new EmbeddingProviderStatus(id, provider.modelId(), true,
                    provider.quotaLeft() && !failedLast, determinism));
        }
        boolean live = commentary != null && commentary.live();
        return new Providers(state.activeImageProvider(), chain, split, dispatcher.length(), image, embedding,
                new CommentaryStatus(props.commentary().model(), live));
    }

    private static List<String> ids(List<String> raw) {
        List<String> ids = new ArrayList<>();
        for (String id : raw) {
            String trimmed = id.strip();
            if (!trimmed.isEmpty() && !ids.contains(trimmed)) {
                ids.add(trimmed);
            }
        }
        return ids;
    }

    /** The configured model of a provider without an adapter (no key), so admins see what would run. */
    private String configuredModel(String id) {
        return switch (id) {
            case "fal" -> props.fal().model();
            case "bfl" -> props.bfl().model();
            case "gemini" -> props.gemini().embeddingModel();
            case "cohere" -> props.cohere().embeddingModel();
            case FakeImageGenerator.ID, FakeImageGenerator.SECOND_ID -> props.fake().model();
            default -> null;
        };
    }
}
