package io.github.ewcastroh.promptbattle.admin;

import io.github.ewcastroh.promptbattle.city.GameRuntime;
import io.github.ewcastroh.promptbattle.commentary.AnthropicChat;
import io.github.ewcastroh.promptbattle.commentary.CommentaryPort;
import io.github.ewcastroh.promptbattle.commentary.SpringAiCommentary;
import io.github.ewcastroh.promptbattle.game.Commentary;
import io.github.ewcastroh.promptbattle.game.Effect;
import io.github.ewcastroh.promptbattle.game.GameEngine;
import io.github.ewcastroh.promptbattle.generation.FakeImageGenerator;
import io.github.ewcastroh.promptbattle.generation.GenerationDispatcher.Dispatched;
import io.github.ewcastroh.promptbattle.generation.GenerationRequest;
import io.github.ewcastroh.promptbattle.generation.GenerationResult;
import io.github.ewcastroh.promptbattle.generation.ProviderRegistry;
import io.github.ewcastroh.promptbattle.persistence.ImageStore;
import io.github.ewcastroh.promptbattle.scoring.DeterminismCheck;
import io.github.ewcastroh.promptbattle.scoring.EmbeddingException;
import io.github.ewcastroh.promptbattle.scoring.EmbeddingService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ContentFilteredException;

/**
 * The provider probe (SPEC §7.6, §4.2), the first thing each admin runs on event day. In parallel:
 * <ul>
 * <li>one real image per configured image provider, through the shared FIFO and the provider's slots, even
 * while its circuit is open (a success closes it);</li>
 * <li>for each configured embedding provider, the same local test image embedded twice without the cache:
 * the max difference decides averaging (stored by the domain, alert DETERMINISM_AVERAGING);</li>
 * <li>one commentary call, or {@code NOT_CONFIGURED} when no commentary model is configured (no key, or the fake profile).</li>
 * </ul>
 * It spends real credits, so concurrent requests (both admins at once) share one run.
 */
public final class ProbeService {

    private static final Logger log = LoggerFactory.getLogger(ProbeService.class);
    static final String PROMPT = "a red bicycle leaning on a white wall, soft daylight";
    static final long SEED = 42;
    static final String COMMENTARY_PROVIDER = "anthropic";

    /** One line of the probe report (API.md {@code POST /api/admin/providers/probe}). */
    public record Result(String kind, String providerId, String model, boolean ok, Long latencyMs, String errorKind,
            Double determinismMaxDiff) {
    }

    public record Report(Instant startedAt, List<Result> results) {
    }

    private final GameRuntime runtime;
    private final ProviderRegistry registry;
    private final EmbeddingService embeddings;
    private final CommentaryPort commentary;
    private final String commentaryModel;
    private final ImageStore images;
    private final GameEngine engine;
    private final Clock clock;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private CompletableFuture<Report> running;

    public ProbeService(GameRuntime runtime, ProviderRegistry registry, EmbeddingService embeddings,
            CommentaryPort commentary, String commentaryModel, ImageStore images, GameEngine engine, Clock clock) {
        this.runtime = runtime;
        this.registry = registry;
        this.embeddings = embeddings;
        this.commentary = commentary;
        this.commentaryModel = commentaryModel;
        this.images = images;
        this.engine = engine;
        this.clock = clock;
    }

    /** Runs the probe, or joins the one already running. */
    public Report run() {
        CompletableFuture<Report> mine;
        synchronized (this) {
            if (running == null || running.isDone()) {
                running = CompletableFuture.supplyAsync(this::probe, executor);
            }
            mine = running;
        }
        try {
            return mine.join();
        } catch (CompletionException e) {
            throw e.getCause() instanceof RuntimeException re ? re : e;
        }
    }

    private Report probe() {
        Instant startedAt = clock.instant();
        Duration imageWait = runtime.imageTimeout().plusSeconds(5);
        List<CompletableFuture<Result>> image = new ArrayList<>();
        for (String id : registry.listed()) {
            image.add(CompletableFuture.supplyAsync(() -> probeImage(id, imageWait), executor));
        }
        byte[] testImage = FakeImageGenerator.render(PROMPT, SEED, GenerationRequest.SIZE, GenerationRequest.SIZE);
        List<CompletableFuture<Result>> embedding = new ArrayList<>();
        for (String id : embeddings.providerIds()) {
            embedding.add(CompletableFuture.supplyAsync(() -> probeEmbedding(id, testImage), executor));
        }
        CompletableFuture<Result> comment = CompletableFuture.supplyAsync(this::probeCommentary, executor);
        List<Result> results = new ArrayList<>();
        image.forEach(f -> results.add(f.join()));
        embedding.forEach(f -> results.add(f.join()));
        results.add(comment.join());
        return new Report(startedAt, List.copyOf(results));
    }

    private Result probeImage(String providerId, Duration wait) {
        String model = registry.generator(providerId).modelId();
        Dispatched d = runtime.generatePinned(providerId, GenerationRequest.square(PROMPT, SEED), true, wait);
        return switch (d.result()) {
            case GenerationResult.Success s -> new Result("IMAGE", providerId, model, true, d.latencyMs(), null, null);
            case GenerationResult.Failure f -> {
                log.warn("Probe: image provider {} failed: {}", providerId, f.kind());
                yield new Result("IMAGE", providerId, model, false, d.latencyMs(), f.kind().name(), null);
            }
        };
    }

    private Result probeEmbedding(String providerId, byte[] image) {
        String model = embeddings.provider(providerId).modelId();
        long started = System.nanoTime();
        try {
            double[] first = embeddings.embedBytes(providerId, image, "image/jpeg");
            long latency = (System.nanoTime() - started) / 1_000_000;
            double[] second = embeddings.embedBytes(providerId, image, "image/jpeg");
            double maxDiff = DeterminismCheck.maxDiff(first, second);
            boolean averaging = DeterminismCheck.needsAveraging(maxDiff);
            engine.run(g -> g.determinismChecked(providerId, maxDiff, averaging));
            log.info("Probe: embedding provider {} ok, {} dims, max diff {}", providerId, first.length, maxDiff);
            return new Result("EMBEDDING", providerId, model, true, latency, null, maxDiff);
        } catch (EmbeddingException e) {
            log.warn("Probe: embedding provider {} failed: {}", providerId, e.kind());
            return new Result("EMBEDDING", providerId, model, false, (System.nanoTime() - started) / 1_000_000,
                    e.kind().name(), null);
        }
    }

    private Result probeCommentary() {
        if (!commentary.live()) {
            return new Result("COMMENTARY", COMMENTARY_PROVIDER, commentaryModel, false, null, "NOT_CONFIGURED", null);
        }
        long started = System.nanoTime();
        try {
            String imageId = images.store(FakeImageGenerator.render(PROMPT, SEED, 512, 512));
            Commentary c = CompletableFuture.supplyAsync(() -> commentary.comment(
                            new Effect.CommentaryJob("probe", imageId, imageId, 50.0, 1, null)), executor)
                    .get(runtime.commentaryTimeout().toMillis(), TimeUnit.MILLISECONDS);
            boolean ok = c != null && c.es() != null && !c.es().isBlank() && c.en() != null && !c.en().isBlank();
            return new Result("COMMENTARY", COMMENTARY_PROVIDER, commentaryModel, ok,
                    (System.nanoTime() - started) / 1_000_000, ok ? null : "INVALID_RESPONSE", null);
        } catch (java.util.concurrent.TimeoutException e) {
            return new Result("COMMENTARY", COMMENTARY_PROVIDER, commentaryModel, false,
                    (System.nanoTime() - started) / 1_000_000, "TIMEOUT", null);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Throwable cause = e instanceof java.util.concurrent.ExecutionException && e.getCause() != null
                    ? e.getCause() : e;
            boolean invalid = cause instanceof SpringAiCommentary.InvalidCommentaryException
                    || cause instanceof AnthropicChat.ModelRefusedException || cause instanceof ContentFilteredException
                    || cause instanceof IllegalStateException;
            return new Result("COMMENTARY", COMMENTARY_PROVIDER, commentaryModel, false,
                    (System.nanoTime() - started) / 1_000_000, invalid ? "INVALID_RESPONSE" : "PROVIDER_ERROR", null);
        }
    }
}
