package io.github.ewcastroh.promptbattle.scoring;

import io.github.ewcastroh.promptbattle.game.Effect;
import io.github.ewcastroh.promptbattle.game.Outcomes;
import io.github.ewcastroh.promptbattle.game.ScoreCalculator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scores one city run (SPEC §4.1, §4.3). Every vector of the run comes from ONE provider: the run's, or, if
 * it fails, the next one of EMBEDDING_PROVIDER_CHAIN, which re-embeds the target, the anchors and every entry.
 * Each provider gets a second try (cheap: successful embeddings are cached) unless its daily quota is gone.
 * Images are embedded in parallel; the service bounds the calls per provider. Returns the cosines; the domain
 * computes the floor and the scores.
 */
public final class RunScorer {

    private static final Logger log = LoggerFactory.getLogger(RunScorer.class);
    static final int TRIES_PER_PROVIDER = 2;

    private final EmbeddingService embeddings;
    private final List<String> chain;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /** @param chain configured embedding providers in failover order */
    public RunScorer(EmbeddingService embeddings, List<String> chain) {
        this.embeddings = embeddings;
        this.chain = List.copyOf(chain);
    }

    public Outcomes.Scored score(Effect.ScoreRun run) {
        EmbeddingException last = null;
        for (String provider : order(run.embeddingProvider())) {
            for (int attempt = 1; attempt <= TRIES_PER_PROVIDER; attempt++) {
                try {
                    return scoreWith(run, provider);
                } catch (EmbeddingException e) {
                    last = e;
                    log.warn("Scoring round {} in {} with {} failed (try {}): {} {}", run.roundId(), run.city(),
                            provider, attempt, e.kind(), e.getMessage());
                    if (e.kind() == EmbeddingException.Kind.QUOTA_EXHAUSTED) {
                        break;
                    }
                }
            }
        }
        throw last != null ? last : new EmbeddingException("No embedding provider configured");
    }

    /** The run's provider first, then the rest of the chain. */
    List<String> order(String first) {
        List<String> order = new ArrayList<>();
        if (embeddings.has(first)) {
            order.add(first);
        }
        for (String p : chain) {
            if (embeddings.has(p) && !order.contains(p)) {
                order.add(p);
            }
        }
        return order;
    }

    private Outcomes.Scored scoreWith(Effect.ScoreRun run, String provider) {
        boolean average = embeddings.averaging(provider); // one mode for every vector of the run
        LinkedHashSet<String> imageIds = new LinkedHashSet<>();
        imageIds.add(run.targetImageId());
        imageIds.addAll(run.anchorImageIds());
        imageIds.addAll(run.entryImageIds().values());
        Map<String, Future<double[]>> futures = new LinkedHashMap<>();
        for (String id : imageIds) {
            futures.put(id, executor.submit(() -> embeddings.embed(id, provider, average)));
        }
        Map<String, double[]> vectors = new HashMap<>();
        try {
            for (Map.Entry<String, Future<double[]>> e : futures.entrySet()) {
                vectors.put(e.getKey(), get(e.getValue()));
            }
        } catch (EmbeddingException e) {
            // Never interrupt: other tasks own cache entries shared with the retry and the other city's
            // scoring. Calls in flight finish and fill the cache; only calls not yet started are dropped.
            futures.values().forEach(f -> f.cancel(false));
            throw e;
        }
        try {
            double[] target = vectors.get(run.targetImageId());
            List<Double> anchors = new ArrayList<>();
            for (String anchor : run.anchorImageIds()) {
                anchors.add(ScoreCalculator.cosine(target, vectors.get(anchor)));
            }
            Map<String, Double> entries = new LinkedHashMap<>();
            run.entryImageIds().forEach((attemptId, imageId) ->
                    entries.put(attemptId, ScoreCalculator.cosine(target, vectors.get(imageId))));
            return new Outcomes.Scored(provider, anchors, entries);
        } catch (IllegalArgumentException e) {
            throw new EmbeddingException(EmbeddingException.Kind.INVALID_RESPONSE, e.getMessage());
        }
    }

    private static double[] get(Future<double[]> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EmbeddingException(EmbeddingException.Kind.TIMEOUT, "Interrupted");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof EmbeddingException ee) {
                throw ee;
            }
            throw new EmbeddingException("Embedding failed", e.getCause());
        }
    }
}
