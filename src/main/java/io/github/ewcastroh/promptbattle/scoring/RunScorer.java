package io.github.ewcastroh.promptbattle.scoring;

import io.github.ewcastroh.promptbattle.game.Effect;
import io.github.ewcastroh.promptbattle.game.Outcomes;
import io.github.ewcastroh.promptbattle.game.ScoreCalculator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Embeds the target, anchors and entries of one city run with one provider and returns their cosines;
 * the floor and scores are computed by the domain (SPEC §4.1). A2 adds failover across providers.
 */
public final class RunScorer {

    private final EmbeddingService embeddings;

    public RunScorer(EmbeddingService embeddings) {
        this.embeddings = embeddings;
    }

    public Outcomes.Scored score(Effect.ScoreRun run) {
        String provider = run.embeddingProvider();
        double[] target = embeddings.embed(run.targetImageId(), provider);
        List<Double> anchors = new ArrayList<>();
        for (String anchor : run.anchorImageIds()) {
            anchors.add(ScoreCalculator.cosine(target, embeddings.embed(anchor, provider)));
        }
        Map<String, Double> entries = new LinkedHashMap<>();
        run.entryImageIds().forEach((attemptId, imageId) ->
                entries.put(attemptId, ScoreCalculator.cosine(target, embeddings.embed(imageId, provider))));
        return new Outcomes.Scored(provider, anchors, entries);
    }
}
