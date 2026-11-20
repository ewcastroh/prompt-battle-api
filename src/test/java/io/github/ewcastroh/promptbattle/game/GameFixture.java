package io.github.ewcastroh.promptbattle.game;

import io.github.ewcastroh.promptbattle.game.Outcomes.AnchorImage;
import io.github.ewcastroh.promptbattle.game.Outcomes.Calibrated;
import io.github.ewcastroh.promptbattle.game.Outcomes.Failed;
import io.github.ewcastroh.promptbattle.game.Outcomes.Generated;
import io.github.ewcastroh.promptbattle.game.Outcomes.Scored;
import io.github.ewcastroh.promptbattle.persistence.SnapshotCodec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

/** A game with two cities, a hand-moved clock and captured effects. Async work is completed by hand. */
final class GameFixture {

    static final String MDE = "mde";
    static final String BOG = "bog";
    static final List<String> ANCHOR_PROMPTS = List.of("a bowl of soup on a table", "a red bicycle",
            "a mountain landscape", "a city street at night", "a cat sleeping", "abstract colorful shapes");

    final MutableClock clock = new MutableClock(Instant.parse("2026-12-02T15:00:00Z"));
    final EventState state = new EventState();
    final Set<String> blockedWords = new HashSet<>();
    final Game game;
    private final List<Effect> pending = new ArrayList<>();
    private final SnapshotCodec codec = new SnapshotCodec();
    private int images;

    GameFixture() {
        this(settings(true));
    }

    GameFixture(GameSettings settings) {
        Map<String, String> cities = new LinkedHashMap<>();
        cities.put(MDE, "Medellín");
        cities.put(BOG, "Bogotá");
        state.syncCities(cities);
        Moderation moderation = new Moderation() {
            @Override
            public boolean blocksNickname(String nickname) {
                return blockedWords.stream().anyMatch(w -> nickname.toLowerCase().contains(w));
            }

            @Override
            public boolean blocksPrompt(String prompt) {
                return blockedWords.stream().anyMatch(w -> prompt.toLowerCase().contains(w));
            }
        };
        game = new Game(state, settings, clock, moderation,
                (key, score) -> new Commentary("canned-es " + key, "canned-en " + key), QueuePositions.NONE,
                new SplittableRandom(42));
    }

    static GameSettings settings(boolean miniPodium) {
        return new GameSettings(20, 3, miniPodium, false, ANCHOR_PROMPTS, List.of("fake"), List.of("fake"),
                "https://ui.test/");
    }

    // ------------------------------------------------------------------ effects

    List<Effect> effects() {
        pending.addAll(game.drainEffects());
        return pending;
    }

    <T extends Effect> List<T> take(Class<T> type) {
        effects();
        List<T> result = new ArrayList<>();
        for (Iterator<Effect> it = pending.iterator(); it.hasNext(); ) {
            Effect e = it.next();
            if (type.isInstance(e)) {
                result.add(type.cast(e));
                it.remove();
            }
        }
        return result;
    }

    void clearEffects() {
        effects();
        pending.clear();
    }

    // ------------------------------------------------------------------ setup helpers

    String image() {
        return "img-" + (++images);
    }

    String join(String city, String nickname) {
        return game.join(city, nickname, null).body().token();
    }

    String playerId(String token) {
        return game.authenticate(token).id;
    }

    String practiceRound() {
        Views.RoundDefinitionView v = game.createRound(
                new RoundInput("Practice", "a red apple", 180, 3, false, 7L), new TargetChoice.Uploaded(image()));
        clearEffects();
        return v.id();
    }

    /** A scored round with READY calibration. */
    String scoredRound(String name) {
        Views.RoundDefinitionView v = game.createRound(
                new RoundInput(name, "hidden " + name, 480, 3, true, 11L), new TargetChoice.Uploaded(image()));
        completeCalibration(v.id());
        return v.id();
    }

    void completeCalibration(String roundId) {
        for (Effect.Calibrate c : take(Effect.Calibrate.class)) {
            List<AnchorImage> anchors = new ArrayList<>();
            for (String prompt : c.prompts()) {
                anchors.add(new AnchorImage(prompt, image()));
            }
            game.calibrationFinished(c.roundId(), c.version(), new Calibrated(c.provider(), anchors));
        }
    }

    String attempt(String token, String prompt) {
        return game.submitAttempt(token, prompt);
    }

    /** Submits and completes a successful attempt. */
    String done(String token, String prompt) {
        String id = attempt(token, prompt);
        succeed(id);
        return id;
    }

    void succeed(String attemptId) {
        game.attemptStarted(attemptId);
        game.attemptFinished(attemptId, new Generated(image(), "fake", "fake-v1"));
    }

    void fail(String attemptId, FailureKind kind) {
        game.attemptStarted(attemptId);
        game.attemptFinished(attemptId, new Failed(kind, "fake"));
    }

    /** Completes the pending scoring effect of a run with the given cosines (attempt id → cos); others 0. */
    void score(String city, String roundId, Map<String, Double> cosByAttempt) {
        for (Effect.ScoreRun s : take(Effect.ScoreRun.class)) {
            if (!s.city().equals(city) || !s.roundId().equals(roundId)) {
                pending.add(s);
                continue;
            }
            Map<String, Double> cos = new LinkedHashMap<>();
            for (String attemptId : s.entryImageIds().keySet()) {
                cos.put(attemptId, cosByAttempt.getOrDefault(attemptId, 0.0));
            }
            List<Double> anchors = new ArrayList<>();
            for (int i = 0; i < s.anchorImageIds().size(); i++) {
                anchors.add(0.0);
            }
            game.scoringFinished(city, roundId, new Scored(s.embeddingProvider(), anchors, cos));
        }
    }

    /** Answers every pending commentary request with an "ai" comment. */
    void answerCommentary() {
        for (Effect.WriteCommentary w : take(Effect.WriteCommentary.class)) {
            Map<String, Commentary> results = new LinkedHashMap<>();
            for (Effect.CommentaryJob job : w.jobs()) {
                results.put(job.key(), new Commentary("ai-es " + job.key(), "ai-en " + job.key()));
            }
            game.commentaryFinished(w.city(), results);
        }
    }

    /** Closes the open round of a city (no in-flight work) and completes scoring and commentary. */
    void closeAndScore(String city, String roundId, Map<String, Double> cos) {
        game.closeRound(city, roundId);
        score(city, roundId, cos);
        answerCommentary();
    }

    Phase phase(String city) {
        return state.cities.get(city).phase;
    }

    RoundRun run(String city, String roundId) {
        return state.cities.get(city).runs.get(roundId);
    }

    String cityJson(String city) {
        return codec.encodeToString(state.cities.get(city));
    }
}
