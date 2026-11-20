package io.github.ewcastroh.promptbattle.city;

import io.github.ewcastroh.promptbattle.commentary.CommentaryPort;
import io.github.ewcastroh.promptbattle.game.CannedCommentary;
import io.github.ewcastroh.promptbattle.game.Commentary;
import io.github.ewcastroh.promptbattle.game.Effect;
import io.github.ewcastroh.promptbattle.game.EffectSink;
import io.github.ewcastroh.promptbattle.game.EventState;
import io.github.ewcastroh.promptbattle.game.FailureKind;
import io.github.ewcastroh.promptbattle.game.Game;
import io.github.ewcastroh.promptbattle.game.GameEngine;
import io.github.ewcastroh.promptbattle.game.GameViews;
import io.github.ewcastroh.promptbattle.game.Outcomes;
import io.github.ewcastroh.promptbattle.game.Views;
import io.github.ewcastroh.promptbattle.generation.GenerationDispatcher;
import io.github.ewcastroh.promptbattle.generation.GenerationDispatcher.Dispatched;
import io.github.ewcastroh.promptbattle.generation.GenerationRequest;
import io.github.ewcastroh.promptbattle.generation.GenerationResult;
import io.github.ewcastroh.promptbattle.generation.ProviderRegistry;
import io.github.ewcastroh.promptbattle.persistence.ImageStore;
import io.github.ewcastroh.promptbattle.persistence.SnapshotCodec;
import io.github.ewcastroh.promptbattle.persistence.SnapshotStore;
import io.github.ewcastroh.promptbattle.scoring.EmbeddingRateLimiter;
import io.github.ewcastroh.promptbattle.scoring.EmbeddingService;
import io.github.ewcastroh.promptbattle.scoring.RunScorer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Executes the game's effects (generation, calibration, scoring, commentary, snapshots) off the engine
 * thread and feeds results back as engine commands. Also drives the server-side timers and the periodic
 * snapshot (SPEC §7.4), and reports circuit transitions and embedding quota usage to the domain.
 * <p>Threads: the engine thread runs commands and {@link #accept} (which only enqueues); the dispatcher
 * starts generations on virtual threads; workers (virtual threads) run calibration, scoring, commentary and
 * prefetches; the timer thread posts {@code tick} and snapshots. Nothing here blocks the engine thread.
 */
public final class GameRuntime implements EffectSink, SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GameRuntime.class);
    public static final Duration COMMENTARY_TIMEOUT = Duration.ofSeconds(8);
    /** Extra wait for a pinned job so the adapter's own timeout normally reports first. */
    static final Duration WAIT_MARGIN = Duration.ofSeconds(5);

    private final GenerationDispatcher dispatcher;
    private final ProviderRegistry registry;
    private final ImageStore images;
    private final EmbeddingService embeddings;
    private final RunScorer scorer;
    private final CommentaryPort commentary;
    private final CannedCommentary fallback;
    private final SnapshotStore snapshots;
    private final SnapshotCodec codec;
    private final Duration tickInterval;
    private final Duration snapshotInterval;
    private final Duration imageTimeout;
    private final boolean restored;

    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ExecutorService snapshotWriter = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("snapshot-writer").daemon().factory());
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("game-timers").daemon().factory());
    private volatile GameEngine engine;
    private volatile boolean running;
    private volatile byte[] lastWritten;

    public GameRuntime(GenerationDispatcher dispatcher, ImageStore images, EmbeddingService embeddings,
            RunScorer scorer, CommentaryPort commentary, CannedCommentary fallback, SnapshotStore snapshots,
            SnapshotCodec codec, Duration tickInterval, Duration snapshotInterval, Duration imageTimeout,
            boolean restored) {
        this.dispatcher = dispatcher;
        this.registry = dispatcher.registry();
        this.images = images;
        this.embeddings = embeddings;
        this.scorer = scorer;
        this.commentary = commentary;
        this.fallback = fallback;
        this.snapshots = snapshots;
        this.codec = codec;
        this.tickInterval = tickInterval;
        this.snapshotInterval = snapshotInterval;
        this.imageTimeout = imageTimeout;
        this.restored = restored;
    }

    public void attach(GameEngine engine) {
        this.engine = engine;
    }

    public Duration imageTimeout() {
        return imageTimeout;
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void start() {
        running = true;
        registry.setListener(new ProviderRegistry.Listener() {
            @Override
            public void circuitOpened(String providerId) {
                engine.post(g -> g.imageCircuitOpened(providerId));
                dispatcher.wake();
            }

            @Override
            public void circuitClosed(String providerId) {
                engine.post(g -> g.imageCircuitClosed(providerId));
                dispatcher.wake();
            }
        });
        Views.ProviderState persisted = engine.read(GameViews::providerState);
        for (String id : embeddings.providerIds()) {
            EmbeddingRateLimiter limiter = embeddings.provider(id).rateLimiter();
            if (limiter == null) {
                continue;
            }
            Views.EmbeddingProviderView saved = persisted.embedding().get(id);
            if (saved != null && saved.usageDay() != null) {
                limiter.restore(saved.usageDay(), saved.usedToday());
            }
            limiter.setListener((day, count, limit) -> engine.post(g -> g.embeddingUsage(id, day, count, limit)));
        }
        engine.run(Game::publishProviderState);
        if (restored) {
            engine.run(Game::resumeAfterRestore);
        }
        scheduler.scheduleWithFixedDelay(() -> engine.post(Game::tick), tickInterval.toMillis(),
                tickInterval.toMillis(), TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(this::periodicSnapshot, snapshotInterval.toMillis(),
                snapshotInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void stop() {
        running = false;
        scheduler.shutdownNow();
        try {
            byte[] json = engine.execute(g -> codec.encode(g.state()));
            snapshotWriter.submit(() -> writeIfChanged(json)).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("Final snapshot failed", e);
        }
        engine.close();
        dispatcher.close();
        workers.shutdownNow();
        snapshotWriter.shutdown();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return 0; // before the web server starts accepting requests
    }

    /** Encodes the state on the engine thread and writes it in the background if it changed. */
    public void periodicSnapshot() {
        engine.post(g -> {
            byte[] json = codec.encode(g.state());
            snapshotWriter.execute(() -> writeIfChanged(json));
        });
    }

    private void writeIfChanged(byte[] json) {
        if (lastWritten != null && Arrays.equals(lastWritten, json)) {
            return;
        }
        try {
            snapshots.write(json);
            lastWritten = json;
        } catch (RuntimeException e) {
            log.error("Snapshot write failed", e);
        }
    }

    // ------------------------------------------------------------------ effects (engine thread; never block)

    @Override
    public void accept(EventState state, List<Effect> effects) {
        for (Effect effect : effects) {
            switch (effect) {
                case Effect.GenerateAttempt g -> generate(g);
                case Effect.CancelGeneration c -> dispatcher.cancel(c.attemptId());
                case Effect.Calibrate c -> workers.execute(() -> calibrate(c));
                case Effect.ScoreRun s -> workers.execute(() -> score(s));
                case Effect.WriteCommentary w -> workers.execute(() -> comment(w));
                case Effect.PrefetchEmbedding p -> workers.execute(() -> embeddings.prefetch(p.imageId(), p.provider()));
                case Effect.ActiveImageProvider a -> {
                    registry.setActive(a.providerId());
                    if (a.resetCircuit()) {
                        registry.reset(a.providerId());
                    }
                    dispatcher.wake();
                }
                case Effect.EmbeddingAveraging e -> embeddings.setAveraging(e.providerId(), e.averaging());
                case Effect.Snapshot s -> {
                    byte[] json = codec.encode(state);
                    snapshotWriter.execute(() -> writeIfChanged(json));
                }
                case Effect.ResetStorage r -> resetStorage();
            }
        }
    }

    /**
     * Event reset: waits for snapshot writes already queued (so an old state can't be written back after the
     * delete), then deletes the snapshot and every image. Blocks the engine briefly; reset is a rare admin action.
     */
    private void resetStorage() {
        dispatcher.cancelAll();
        registry.resetAll();
        embeddings.clear();
        try {
            snapshotWriter.submit(() -> {
                images.deleteAll();
                snapshots.delete();
                lastWritten = null;
            }).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("Event reset could not delete the stored data", e);
        }
    }

    private void generate(Effect.GenerateAttempt g) {
        String id = g.attemptId();
        dispatcher.submit(id, GenerationDispatcher.Target.FLEXIBLE, GenerationRequest.square(g.prompt(), g.seed()),
                        provider -> engine.post(game -> game.attemptStarted(id, provider)))
                .thenAccept(d -> {
                    Outcomes.AttemptOutcome outcome = toOutcome(d.result(), d.providerId());
                    engine.post(game -> game.attemptFinished(id, outcome));
                });
    }

    private Outcomes.AttemptOutcome toOutcome(GenerationResult result, String provider) {
        return switch (result) {
            case GenerationResult.Success s -> {
                try {
                    yield new Outcomes.Generated(images.store(s.bytes()), provider, s.modelId());
                } catch (RuntimeException e) {
                    log.warn("Provider {} returned an unusable image", provider);
                    yield new Outcomes.Failed(FailureKind.INVALID_RESPONSE, provider);
                }
            }
            case GenerationResult.Failure f -> new Outcomes.Failed(f.kind(), provider);
        };
    }

    /**
     * Generates one image on one provider through the shared FIFO and waits (never on the engine thread).
     * {@code probe} also runs while the provider's circuit is open. A job still queued at the timeout is
     * cancelled; one already running finishes and its result is dropped.
     */
    public Dispatched generatePinned(String provider, GenerationRequest request, boolean probe, Duration timeout) {
        String jobId = UUID.randomUUID().toString();
        CompletableFuture<Dispatched> future = dispatcher.submit(jobId,
                new GenerationDispatcher.Target.Pinned(provider, probe), request, null);
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            dispatcher.cancel(jobId);
            return new Dispatched(provider, new GenerationResult.Failure(FailureKind.TIMEOUT, "timeout"),
                    timeout.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            dispatcher.cancel(jobId);
            return new Dispatched(provider, new GenerationResult.Failure(FailureKind.PROVIDER_ERROR, "interrupted"), 0);
        } catch (java.util.concurrent.ExecutionException e) {
            return new Dispatched(provider, new GenerationResult.Failure(FailureKind.PROVIDER_ERROR, "failed"), 0);
        }
    }

    /** A target preview (admin, synchronous): up to IMAGE_TIMEOUT_SECONDS plus a small margin. */
    public GenerationResult generateAndWait(String provider, GenerationRequest request) {
        return generatePinned(provider, request, false, imageTimeout.plus(WAIT_MARGIN)).result();
    }

    private void calibrate(Effect.Calibrate c) {
        Duration timeout = imageTimeout.multipliedBy(2).plus(WAIT_MARGIN); // anchors may queue behind players
        List<CompletableFuture<GenerationResult>> futures = new ArrayList<>();
        for (String prompt : c.prompts()) {
            futures.add(CompletableFuture.supplyAsync(() -> generatePinned(c.provider(),
                    GenerationRequest.square(prompt, c.seed()), false, timeout).result(), workers));
        }
        List<Outcomes.AnchorImage> anchors = new ArrayList<>();
        Outcomes.CalibrationOutcome outcome;
        try {
            for (int i = 0; i < futures.size(); i++) {
                GenerationResult r = futures.get(i).join();
                if (!(r instanceof GenerationResult.Success s)) {
                    throw new IllegalStateException("anchor failed: " + ((GenerationResult.Failure) r).kind());
                }
                anchors.add(new Outcomes.AnchorImage(c.prompts().get(i), images.store(s.bytes())));
            }
            outcome = new Outcomes.Calibrated(c.provider(), anchors);
        } catch (RuntimeException e) {
            log.warn("Calibration of round {} failed: {}", c.roundId(), e.getMessage());
            outcome = new Outcomes.CalibrationFailed(e.getMessage());
        }
        Outcomes.CalibrationOutcome result = outcome;
        engine.post(g -> g.calibrationFinished(c.roundId(), c.version(), result));
    }

    private void score(Effect.ScoreRun s) {
        Outcomes.ScoringOutcome outcome;
        try {
            outcome = scorer.score(s);
        } catch (RuntimeException e) {
            log.warn("Scoring of round {} in {} failed: {}", s.roundId(), s.city(), e.getMessage());
            outcome = new Outcomes.ScoringFailed(e.getMessage());
        }
        Outcomes.ScoringOutcome result = outcome;
        engine.post(g -> g.scoringFinished(s.city(), s.roundId(), result));
    }

    private void comment(Effect.WriteCommentary w) {
        Map<Effect.CommentaryJob, CompletableFuture<Commentary>> futures = new LinkedHashMap<>();
        for (Effect.CommentaryJob job : w.jobs()) {
            futures.put(job, CompletableFuture.supplyAsync(() -> commentary.comment(job), workers)
                    .orTimeout(COMMENTARY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                    .exceptionally(e -> fallback.canned(job.key(), job.score())));
        }
        Map<String, Commentary> results = new LinkedHashMap<>();
        futures.forEach((job, f) -> {
            Commentary c = f.join();
            results.put(job.key(), c != null ? c : fallback.canned(job.key(), job.score()));
        });
        engine.post(g -> g.commentaryFinished(w.city(), results));
    }
}
