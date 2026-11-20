package io.github.ewcastroh.promptbattle.city;

import io.github.ewcastroh.promptbattle.commentary.CommentaryPort;
import io.github.ewcastroh.promptbattle.game.CannedCommentary;
import io.github.ewcastroh.promptbattle.game.Commentary;
import io.github.ewcastroh.promptbattle.game.Effect;
import io.github.ewcastroh.promptbattle.game.EffectSink;
import io.github.ewcastroh.promptbattle.game.EventState;
import io.github.ewcastroh.promptbattle.game.FailureKind;
import io.github.ewcastroh.promptbattle.game.GameEngine;
import io.github.ewcastroh.promptbattle.game.Outcomes;
import io.github.ewcastroh.promptbattle.generation.GenerationQueue;
import io.github.ewcastroh.promptbattle.generation.GenerationRequest;
import io.github.ewcastroh.promptbattle.generation.GenerationResult;
import io.github.ewcastroh.promptbattle.persistence.ImageStore;
import io.github.ewcastroh.promptbattle.persistence.SnapshotCodec;
import io.github.ewcastroh.promptbattle.persistence.SnapshotStore;
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
 * snapshot (SPEC §7.4).
 */
public final class GameRuntime implements EffectSink, SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GameRuntime.class);
    static final Duration COMMENTARY_TIMEOUT = Duration.ofSeconds(8);

    private final GenerationQueue queue;
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

    public GameRuntime(GenerationQueue queue, ImageStore images, EmbeddingService embeddings, RunScorer scorer,
            CommentaryPort commentary, CannedCommentary fallback, SnapshotStore snapshots, SnapshotCodec codec,
            Duration tickInterval, Duration snapshotInterval, Duration imageTimeout, boolean restored) {
        this.queue = queue;
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

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void start() {
        running = true;
        if (restored) {
            engine.run(g -> g.resumeAfterRestore());
        }
        scheduler.scheduleWithFixedDelay(() -> engine.post(g -> g.tick()), tickInterval.toMillis(),
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
        queue.close();
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
                case Effect.CancelGeneration c -> queue.cancel(c.attemptId());
                case Effect.Calibrate c -> workers.execute(() -> calibrate(c));
                case Effect.ScoreRun s -> workers.execute(() -> score(s));
                case Effect.WriteCommentary w -> workers.execute(() -> comment(w));
                case Effect.PrefetchEmbedding p -> workers.execute(() -> prefetch(p));
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
        queue.cancelAll();
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
        queue.submit(id, g.provider(), GenerationRequest.square(g.prompt(), g.seed()),
                        () -> engine.post(game -> game.attemptStarted(id)))
                .thenAccept(result -> {
                    Outcomes.AttemptOutcome outcome = toOutcome(result, g.provider());
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

    /** Generates one image through the shared queue and waits (worker threads only). */
    public GenerationResult generateAndWait(String provider, GenerationRequest request) {
        String jobId = UUID.randomUUID().toString();
        CompletableFuture<GenerationResult> future = queue.submit(jobId, provider, request, () -> {
        });
        try {
            return future.get(imageTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            queue.cancel(jobId);
            return new GenerationResult.Failure(FailureKind.TIMEOUT, "timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new GenerationResult.Failure(FailureKind.PROVIDER_ERROR, "interrupted");
        } catch (java.util.concurrent.ExecutionException e) {
            return new GenerationResult.Failure(FailureKind.PROVIDER_ERROR, "failed");
        }
    }

    private void calibrate(Effect.Calibrate c) {
        List<CompletableFuture<GenerationResult>> futures = new ArrayList<>();
        for (String prompt : c.prompts()) {
            futures.add(CompletableFuture.supplyAsync(
                    () -> generateAndWait(c.provider(), GenerationRequest.square(prompt, c.seed())), workers));
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
            outcome = scoreWithRetry(s);
        } catch (RuntimeException e) {
            log.warn("Scoring of round {} in {} failed: {}", s.roundId(), s.city(), e.getMessage());
            outcome = new Outcomes.ScoringFailed(e.getMessage());
        }
        Outcomes.ScoringOutcome result = outcome;
        engine.post(g -> g.scoringFinished(s.city(), s.roundId(), result));
    }

    private Outcomes.Scored scoreWithRetry(Effect.ScoreRun s) {
        try {
            return scorer.score(s);
        } catch (RuntimeException first) {
            log.info("Scoring retry for round {} in {}", s.roundId(), s.city());
            return scorer.score(s);
        }
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

    private void prefetch(Effect.PrefetchEmbedding p) {
        try {
            embeddings.embed(p.imageId(), p.provider());
        } catch (RuntimeException e) {
            log.debug("Prefetch embedding failed for {}", p.imageId());
        }
    }
}
