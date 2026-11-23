package io.github.ewcastroh.promptbattle.game;

import io.github.ewcastroh.promptbattle.game.Effect.CommentaryJob;
import io.github.ewcastroh.promptbattle.game.Outcomes.AnchorImage;
import io.github.ewcastroh.promptbattle.game.Outcomes.AttemptOutcome;
import io.github.ewcastroh.promptbattle.game.Outcomes.Calibrated;
import io.github.ewcastroh.promptbattle.game.Outcomes.CalibrationFailed;
import io.github.ewcastroh.promptbattle.game.Outcomes.CalibrationOutcome;
import io.github.ewcastroh.promptbattle.game.Outcomes.Failed;
import io.github.ewcastroh.promptbattle.game.Outcomes.Generated;
import io.github.ewcastroh.promptbattle.game.Outcomes.Scored;
import io.github.ewcastroh.promptbattle.game.Outcomes.ScoringFailed;
import io.github.ewcastroh.promptbattle.game.Outcomes.ScoringOutcome;
import io.github.ewcastroh.promptbattle.game.Ranking.Candidate;
import io.github.ewcastroh.promptbattle.game.Reveal.Revealed;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * The game rules: every state mutation is a method here, called only on the engine thread
 * ({@link GameEngine}). Methods throw {@link GameException} for rule violations and queue {@link Effect}s
 * for asynchronous work.
 */
public final class Game {

    static final int MINI_PODIUM_SIZE = 3;
    static final int PODIUM_SIZE = 5;
    static final int GALLERY_SIZE = 6;
    static final int MAX_ALERTS = 500;
    static final Duration EXTENSION = Duration.ofSeconds(60);
    static final int BLOCKS_TO_FLAG = 3;
    private static final Set<Phase> START_PHASES = EnumSet.of(Phase.LOBBY, Phase.ROUND_SCORED, Phase.MINI_PODIUM);

    final EventState state;
    final GameSettings settings;
    private final Clock clock;
    private final Moderation moderation;
    final CannedCommentary canned;
    final QueuePositions queue;
    private final ProviderHealth health;
    private final RandomGenerator random;
    private final List<Effect> effects = new ArrayList<>();
    private final GameViews views;

    public Game(EventState state, GameSettings settings, Clock clock, Moderation moderation,
            CannedCommentary canned, QueuePositions queue, ProviderHealth health, RandomGenerator random) {
        this.state = state;
        this.settings = settings;
        this.clock = clock;
        this.moderation = moderation;
        this.canned = canned;
        this.queue = queue;
        this.health = health;
        this.random = random;
        this.views = new GameViews(this);
        if (state.activeImageProvider == null || !settings.imageProviders().contains(state.activeImageProvider)) {
            state.activeImageProvider = settings.imageProviders().isEmpty() ? null : settings.imageProviders().getFirst();
        }
    }

    public EventState state() {
        return state;
    }

    public GameViews views() {
        return views;
    }

    public List<Effect> drainEffects() {
        if (effects.isEmpty()) {
            return List.of();
        }
        List<Effect> drained = List.copyOf(effects);
        effects.clear();
        return drained;
    }

    Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private void emit(Effect effect) {
        effects.add(effect);
    }

    private void snapshot() {
        if (!effects.contains(new Effect.Snapshot())) {
            emit(new Effect.Snapshot());
        }
    }

    // ------------------------------------------------------------------ lookups

    CityRun city(String code) {
        CityRun city = code == null ? null : state.cities.get(code);
        if (city == null) {
            throw new GameException(ErrorCode.CITY_NOT_FOUND, "Unknown city", Map.of("city", String.valueOf(code)));
        }
        return city;
    }

    RoundDefinition round(String roundId) {
        RoundDefinition d = findRound(roundId);
        if (d == null) {
            throw new GameException(ErrorCode.ROUND_NOT_FOUND, "Unknown round " + roundId);
        }
        return d;
    }

    RoundDefinition findRound(String roundId) {
        for (RoundDefinition d : state.rounds) {
            if (d.id.equals(roundId)) {
                return d;
            }
        }
        return null;
    }

    /** Resolves a player token; throws UNAUTHORIZED or PLAYER_KICKED. */
    Player authenticate(String token) {
        Player p = findByToken(token);
        if (p == null) {
            throw new GameException(ErrorCode.UNAUTHORIZED, "Unknown player token");
        }
        if (p.kicked) {
            throw new GameException(ErrorCode.PLAYER_KICKED, "Player was kicked");
        }
        return p;
    }

    private Player findByToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String hash = Tokens.hash(token);
        for (CityRun city : state.cities.values()) {
            String id = city.tokens.get(hash);
            if (id != null) {
                return city.players.get(id);
            }
        }
        return null;
    }

    record Located(CityRun city, RoundRun run, Attempt attempt) {
    }

    Located findAttempt(String attemptId) {
        for (CityRun city : state.cities.values()) {
            for (RoundRun run : city.runs.values()) {
                Attempt a = run.attempts.get(attemptId);
                if (a != null) {
                    return new Located(city, run, a);
                }
            }
        }
        return null;
    }

    /** The player of {@code playerId} if it belongs to the admin's city; ADMIN_FORBIDDEN or PLAYER_NOT_FOUND. */
    private Player ownPlayer(String adminCity, String playerId) {
        for (CityRun city : state.cities.values()) {
            Player p = city.players.get(playerId);
            if (p != null) {
                if (!city.code.equals(adminCity)) {
                    throw new GameException(ErrorCode.ADMIN_FORBIDDEN, "Player belongs to another city");
                }
                return p;
            }
        }
        throw new GameException(ErrorCode.PLAYER_NOT_FOUND, "Unknown player " + playerId);
    }

    // ------------------------------------------------------------------ players

    public Views.JoinResult join(String cityCode, String rawNickname, String existingToken) {
        CityRun city = city(cityCode);
        Player existing = findByToken(existingToken);
        if (existing != null && !existing.kicked && existing.city.equals(city.code)) {
            return new Views.JoinResult(new Views.Joined(existing.id, existingToken, existing.claimCode, city.code),
                    false);
        }
        String nickname = NicknameRules.validate(rawNickname);
        if (moderation.blocksNickname(nickname)) {
            throw new GameException(ErrorCode.NICKNAME_BLOCKED, "Nickname blocked");
        }
        String key = NicknameRules.key(nickname);
        ensureNicknameFree(city, key, null);

        Player p = new Player();
        p.id = UUID.randomUUID().toString();
        p.city = city.code;
        p.nickname = nickname;
        p.nicknameKey = key;
        p.claimCode = uniqueClaimCode(city);
        p.joinedAt = now();
        String token = Tokens.newPlayerToken(random);
        city.players.put(p.id, p);
        city.tokens.put(Tokens.hash(token), p.id);
        return new Views.JoinResult(new Views.Joined(p.id, token, p.claimCode, city.code), true);
    }

    private void ensureNicknameFree(CityRun city, String key, String exceptPlayerId) {
        for (Player other : city.players.values()) {
            if (other.nicknameKey.equals(key) && !other.id.equals(exceptPlayerId)) {
                throw new GameException(ErrorCode.NICKNAME_TAKEN, "Nickname already used in this city");
            }
        }
    }

    private String uniqueClaimCode(CityRun city) {
        while (true) {
            String code = Tokens.newClaimCode(random);
            boolean taken = false;
            for (Player p : city.players.values()) {
                if (p.claimCode.equals(code)) {
                    taken = true;
                    break;
                }
            }
            if (!taken) {
                return code;
            }
        }
    }

    /** Creates a QUEUED attempt in the player's open round and returns its id. */
    public String submitAttempt(String token, String rawPrompt) {
        Player p = authenticate(token);
        CityRun city = city(p.city);
        RoundRun run = city.currentRun();
        if (city.phase != Phase.ROUND_OPEN || run == null || run.status != RunStatus.OPEN) {
            throw new GameException(ErrorCode.ROUND_NOT_OPEN, "No open round in " + city.code);
        }
        String prompt = PromptRules.normalize(rawPrompt, PromptRules.PLAYER_MAX);
        for (Attempt a : run.attemptsOf(p.id)) {
            if (a.status.inFlight()) {
                throw new GameException(ErrorCode.GENERATION_IN_FLIGHT, "Generation already in flight");
            }
        }
        RoundDefinition d = round(run.roundId);
        if (run.doneCount(p.id) >= d.attemptsPerPlayer) {
            throw new GameException(ErrorCode.NO_ATTEMPTS_LEFT, "No attempts left",
                    Map.of("max", d.attemptsPerPlayer));
        }
        if (moderation.blocksPrompt(prompt)) {
            recordBlock(city, run, p);
            throw new GameException(ErrorCode.PROMPT_BLOCKED, "Prompt blocked by the blocklist");
        }
        Attempt a = new Attempt();
        a.id = UUID.randomUUID().toString();
        a.playerId = p.id;
        a.roundId = run.roundId;
        a.city = city.code;
        a.prompt = prompt;
        a.status = AttemptStatus.QUEUED;
        a.provider = run.imageProvider;
        a.createdAt = now();
        run.attempts.put(a.id, a);
        emit(new Effect.GenerateAttempt(a.id, prompt, d.seed));
        return a.id;
    }

    private void recordBlock(CityRun city, RoundRun run, Player p) {
        int blocks = run.blocks.merge(p.id, 1, Integer::sum);
        if (blocks >= BLOCKS_TO_FLAG && !p.flagged) {
            p.flagged = true;
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("playerId", p.id);
            params.put("nickname", p.nickname);
            alert("PLAYER_FLAGGED", city.code, params);
        }
    }

    /** The prompt passed the optional pre-generation steps; {@code promptEn} is its translation (or null). */
    public void attemptPrepared(String attemptId, String promptEn) {
        Located loc = findAttempt(attemptId);
        if (loc != null && loc.attempt().status == AttemptStatus.QUEUED) {
            loc.attempt().promptEn = promptEn;
        }
    }

    /** The dispatcher started the generation on {@code provider} (it may differ from the run's provider). */
    public void attemptStarted(String attemptId, String provider) {
        Located loc = findAttempt(attemptId);
        if (loc != null && loc.attempt().status == AttemptStatus.QUEUED) {
            loc.attempt().status = AttemptStatus.GENERATING;
            if (provider != null) {
                loc.attempt().provider = provider;
            }
        }
    }

    public void attemptFinished(String attemptId, AttemptOutcome outcome) {
        Located loc = findAttempt(attemptId);
        if (loc == null || !loc.attempt().status.inFlight()) {
            return; // unknown, or already discarded after the grace period
        }
        CityRun city = loc.city();
        RoundRun run = loc.run();
        Attempt a = loc.attempt();
        Instant now = now();
        boolean accepting = run.status == RunStatus.OPEN
                || (run.status == RunStatus.CLOSING && now.isBefore(run.graceEndsAt));
        a.finishedAt = now;
        if (!accepting) {
            a.status = AttemptStatus.FAILED;
            a.code = ErrorCode.ROUND_NOT_OPEN;
            return;
        }
        switch (outcome) {
            case Generated g -> {
                a.status = AttemptStatus.DONE;
                a.imageId = g.imageId();
                a.provider = g.provider();
                a.model = g.model();
                if (settings.eagerEmbeddings()) {
                    prefetch(run, a);
                }
                // D-022: during grace the last successful attempt replaces an automatic entry, never a chosen one.
                Entry current = run.entries.get(a.playerId);
                if (run.status == RunStatus.CLOSING && (current == null || current.auto)) {
                    setEntry(run, a, now, true);
                }
            }
            case Outcomes.Moderated m -> {
                a.status = AttemptStatus.BLOCKED;
                a.code = ErrorCode.PROMPT_BLOCKED;
                recordBlock(city, run, city.players.get(a.playerId));
            }
            case Failed f -> {
                a.provider = f.provider() != null ? f.provider() : a.provider;
                if (f.kind() == FailureKind.SAFETY_BLOCKED) {
                    a.status = AttemptStatus.BLOCKED;
                    a.code = ErrorCode.PROMPT_BLOCKED;
                    recordBlock(city, run, city.players.get(a.playerId));
                } else {
                    a.status = AttemptStatus.FAILED;
                    a.code = ErrorCode.GENERATION_FAILED;
                    a.failureKind = f.kind();
                }
            }
        }
        if (run.status == RunStatus.CLOSING && run.inFlight() == 0) {
            finishClosing(city, run);
        }
    }

    public void selectEntry(String token, String attemptId) {
        Player p = authenticate(token);
        CityRun city = city(p.city);
        RoundRun run = city.currentRun();
        if (city.phase != Phase.ROUND_OPEN || run == null || run.status != RunStatus.OPEN) {
            throw new GameException(ErrorCode.ROUND_NOT_OPEN, "No open round in " + city.code);
        }
        Located loc = findAttempt(attemptId);
        if (loc == null || !loc.attempt().playerId.equals(p.id)) {
            throw new GameException(ErrorCode.ATTEMPT_NOT_FOUND, "Unknown attempt " + attemptId);
        }
        Attempt a = loc.attempt();
        if (!a.roundId.equals(run.roundId) || a.status != AttemptStatus.DONE) {
            throw new GameException(ErrorCode.ATTEMPT_NOT_SELECTABLE, "Attempt is not selectable");
        }
        setEntry(run, a, now(), false);
    }

    private void setEntry(RoundRun run, Attempt a, Instant at, boolean auto) {
        Entry e = new Entry();
        e.attemptId = a.id;
        e.playerId = a.playerId;
        e.selectedAt = at;
        e.auto = auto;
        e.hidden = a.hidden; // a hidden image stays hidden when its player selects it again
        run.entries.put(a.playerId, e);
        if (!settings.eagerEmbeddings()) {
            prefetch(run, a);
        }
    }

    private void prefetch(RoundRun run, Attempt a) {
        RoundDefinition d = findRound(run.roundId);
        if (d != null && d.scored && run.embeddingProvider != null && a.imageId != null) {
            emit(new Effect.PrefetchEmbedding(a.imageId, run.embeddingProvider));
        }
    }

    // ------------------------------------------------------------------ round definitions (shared)

    public Views.PreviewView registerPreview(String imageId, long seed, String provider, String model) {
        Preview p = new Preview();
        p.id = UUID.randomUUID().toString();
        p.imageId = imageId;
        p.seed = seed;
        p.provider = provider;
        p.model = model;
        p.createdAt = now();
        state.previews.put(p.id, p);
        return GameViews.preview(p);
    }

    public long randomSeed() {
        return random.nextLong(0, RoundInput.SEED_MAX + 1);
    }

    public String activeImageProvider() {
        return state.activeImageProvider;
    }

    public Views.RoundDefinitionView createRound(RoundInput input, TargetChoice target) {
        input.validate(true);
        if (target == null) {
            throw GameException.validation("previewId");
        }
        Preview preview = target instanceof TargetChoice.FromPreview fp ? takePreview(fp.previewId()) : null;
        RoundDefinition d = new RoundDefinition();
        d.id = UUID.randomUUID().toString();
        d.position = state.nextRoundPosition++;
        d.name = input.name().strip();
        d.scored = input.scored() == null || input.scored();
        d.hiddenPrompt = input.hiddenPrompt().strip();
        d.durationSec = input.durationSec() != null ? input.durationSec() : (d.scored ? 480 : 180);
        d.attemptsPerPlayer = input.attemptsPerPlayer() != null ? input.attemptsPerPlayer() : settings.defaultAttempts();
        d.seed = input.seed() != null ? input.seed() : (preview != null ? preview.seed : randomSeed());
        applyTarget(d, target, preview);
        state.rounds.add(d);
        restartCalibration(d);
        return views.roundDefinition(d);
    }

    private Preview takePreview(String previewId) {
        Preview p = previewId == null ? null : state.previews.remove(previewId);
        if (p == null) {
            throw new GameException(ErrorCode.PREVIEW_NOT_FOUND, "Unknown preview " + previewId);
        }
        return p;
    }

    private static void applyTarget(RoundDefinition d, TargetChoice target, Preview preview) {
        if (preview != null) {
            d.targetImageId = preview.imageId;
            d.targetSource = TargetSource.GENERATED;
        } else if (target instanceof TargetChoice.Uploaded u) {
            d.targetImageId = u.imageId();
            d.targetSource = TargetSource.UPLOADED;
        }
    }

    public Views.RoundDefinitionView updateRound(String roundId, RoundInput input, TargetChoice target) {
        RoundDefinition d = round(roundId);
        if (d.locked) {
            throw new GameException(ErrorCode.ROUND_LOCKED, "Round already started");
        }
        input.validate(false);
        Preview preview = target instanceof TargetChoice.FromPreview fp ? takePreview(fp.previewId()) : null;
        long oldSeed = d.seed;
        boolean oldScored = d.scored;
        if (input.name() != null) {
            d.name = input.name().strip();
        }
        if (input.hiddenPrompt() != null) {
            d.hiddenPrompt = input.hiddenPrompt().strip();
        }
        if (input.scored() != null) {
            d.scored = input.scored();
        }
        if (input.durationSec() != null) {
            d.durationSec = input.durationSec();
        }
        if (input.attemptsPerPlayer() != null) {
            d.attemptsPerPlayer = input.attemptsPerPlayer();
        }
        if (input.seed() != null) {
            d.seed = input.seed();
        } else if (preview != null) {
            d.seed = preview.seed;
        }
        if (target != null) {
            applyTarget(d, target, preview);
        }
        if (target != null || d.seed != oldSeed || d.scored != oldScored) {
            restartCalibration(d);
        }
        return views.roundDefinition(d);
    }

    public void deleteRound(String roundId) {
        RoundDefinition d = round(roundId);
        if (d.locked) {
            throw new GameException(ErrorCode.ROUND_LOCKED, "Round already started");
        }
        state.rounds.remove(d);
    }

    public Views.RoundDefinitionView recalibrate(String roundId) {
        RoundDefinition d = round(roundId);
        if (d.locked) {
            throw new GameException(ErrorCode.ROUND_LOCKED, "Round already started");
        }
        if (!d.scored) {
            throw new GameException(ErrorCode.INVALID_TRANSITION, "Practice rounds need no calibration");
        }
        restartCalibration(d);
        return views.roundDefinition(d);
    }

    private void restartCalibration(RoundDefinition d) {
        Calibration c = d.calibration;
        c.version++;
        c.anchors = new ArrayList<>();
        c.provider = null;
        if (!d.scored) {
            c.status = CalibrationStatus.NOT_REQUIRED;
            return;
        }
        String provider = pinnedImageProvider();
        if (provider == null) {
            c.status = CalibrationStatus.FAILED;
            alert("CALIBRATION_FAILED", null, Map.of("roundId", d.id));
            return;
        }
        c.status = CalibrationStatus.PENDING;
        emit(new Effect.Calibrate(d.id, c.version, provider, d.seed, settings.calibrationPrompts()));
    }

    public void calibrationFinished(String roundId, int version, CalibrationOutcome outcome) {
        RoundDefinition d = findRound(roundId);
        if (d == null || d.calibration.version != version || d.calibration.status != CalibrationStatus.PENDING) {
            return;
        }
        switch (outcome) {
            case Calibrated ok -> {
                d.calibration.status = CalibrationStatus.READY;
                d.calibration.provider = ok.provider();
                List<Calibration.Anchor> anchors = new ArrayList<>();
                for (AnchorImage a : ok.anchors()) {
                    anchors.add(new Calibration.Anchor(a.prompt(), a.imageId()));
                }
                d.calibration.anchors = anchors;
            }
            case CalibrationFailed failed -> {
                d.calibration.status = CalibrationStatus.FAILED;
                alert("CALIBRATION_FAILED", null, Map.of("roundId", d.id));
            }
        }
    }

    // ------------------------------------------------------------------ run control (own city)

    public void startRound(String cityCode, String roundId) {
        CityRun city = city(cityCode);
        RoundDefinition d = round(roundId);
        if (!START_PHASES.contains(city.phase)) {
            throw GameException.invalidTransition(city.phase, "start");
        }
        RoundRun existing = city.runs.get(roundId);
        if (existing != null && existing.status != RunStatus.NOT_STARTED) {
            throw GameException.invalidTransition(city.phase, "start (already run in this city)");
        }
        if (d.scored && d.calibration.status != CalibrationStatus.READY) {
            throw new GameException(ErrorCode.ROUND_NOT_READY, "Calibration not ready",
                    Map.of("calibration", d.calibration.status.name()));
        }
        if (state.activeImageProvider == null || !imageProviderAvailable()) {
            throw new GameException(ErrorCode.PROVIDER_UNAVAILABLE, "No healthy image provider");
        }
        Instant now = now();
        RoundRun run = new RoundRun(roundId, city.code);
        run.status = RunStatus.OPEN;
        run.startedAt = now;
        run.endsAt = now.plusSeconds(d.durationSec);
        run.imageProvider = state.activeImageProvider;
        run.embeddingProvider = settings.embeddingProviders().isEmpty() ? null : settings.embeddingProviders().getFirst();
        city.runs.put(roundId, run);
        city.currentRoundId = roundId;
        city.phase = Phase.ROUND_OPEN;
        city.miniPodium = null;
        city.practiceGallery = null;
        d.locked = true;
        if (d.scored && run.embeddingProvider != null) {
            // Embed the shared target and anchors early (cached per provider, so the other city reuses them).
            emit(new Effect.PrefetchEmbedding(d.targetImageId, run.embeddingProvider));
            for (Calibration.Anchor a : d.calibration.anchors) {
                emit(new Effect.PrefetchEmbedding(a.imageId, run.embeddingProvider));
            }
        }
        snapshot();
    }

    private RoundRun openRunFor(CityRun city, String roundId, String action) {
        round(roundId);
        RoundRun run = city.currentRun();
        if (city.phase != Phase.ROUND_OPEN || run == null || !run.roundId.equals(roundId)
                || run.status != RunStatus.OPEN) {
            throw GameException.invalidTransition(city.phase, action);
        }
        return run;
    }

    public void closeRound(String cityCode, String roundId) {
        CityRun city = city(cityCode);
        close(city, openRunFor(city, roundId, "close"));
    }

    public void extendRound(String cityCode, String roundId) {
        CityRun city = city(cityCode);
        RoundRun run = openRunFor(city, roundId, "extend");
        run.endsAt = run.endsAt.plus(EXTENSION);
        run.extensions++;
    }

    private void close(CityRun city, RoundRun run) {
        Instant now = now();
        run.status = RunStatus.CLOSING;
        run.closedAt = now;
        run.graceEndsAt = now.plusSeconds(settings.graceSeconds());
        city.phase = Phase.ROUND_CLOSING;
        for (Player p : city.players.values()) {
            if (run.entries.containsKey(p.id)) {
                continue;
            }
            Attempt last = null;
            for (Attempt a : run.attemptsOf(p.id)) {
                if (a.status == AttemptStatus.DONE) {
                    last = a;
                }
            }
            if (last != null) {
                setEntry(run, last, now, true);
            }
        }
        snapshot();
        if (run.inFlight() == 0) {
            finishClosing(city, run);
        }
    }

    /** Ends the grace period: discards in-flight work, then practice → LOBBY or scored → scoring. */
    private void finishClosing(CityRun city, RoundRun run) {
        Instant now = now();
        for (Attempt a : run.attempts.values()) {
            if (a.status.inFlight()) {
                a.status = AttemptStatus.FAILED;
                a.code = ErrorCode.ROUND_NOT_OPEN;
                a.finishedAt = now;
                emit(new Effect.CancelGeneration(a.id));
            }
        }
        RoundDefinition d = round(run.roundId);
        if (!d.scored) {
            run.status = RunStatus.CLOSED;
            city.phase = Phase.LOBBY;
        } else {
            run.status = RunStatus.SCORING;
            requestScoring(city, run, d);
        }
        snapshot();
    }

    private void requestScoring(CityRun city, RoundRun run, RoundDefinition d) {
        if (run.embeddingProvider == null && !settings.embeddingProviders().isEmpty()) {
            run.embeddingProvider = settings.embeddingProviders().getFirst();
        }
        if (run.embeddingProvider == null) {
            scoringFailed(city, run);
            return;
        }
        List<String> anchors = new ArrayList<>();
        for (Calibration.Anchor a : d.calibration.anchors) {
            anchors.add(a.imageId);
        }
        Map<String, String> entryImages = new LinkedHashMap<>();
        for (Entry e : run.entries.values()) {
            entryImages.put(e.attemptId, run.attempts.get(e.attemptId).imageId);
        }
        emit(new Effect.ScoreRun(city.code, run.roundId, run.embeddingProvider, d.targetImageId, anchors,
                entryImages));
    }

    private void scoringFailed(CityRun city, RoundRun run) {
        run.status = RunStatus.SCORING_FAILED;
        alert("SCORING_FAILED", city.code, Map.of("roundId", run.roundId));
        snapshot();
    }

    public void scoringFinished(String cityCode, String roundId, ScoringOutcome outcome) {
        CityRun city = state.cities.get(cityCode);
        RoundRun run = city == null ? null : city.runs.get(roundId);
        if (run == null || run.status != RunStatus.SCORING || run.floor != null) {
            return;
        }
        switch (outcome) {
            case Scored s -> {
                double floor = ScoreCalculator.floor(s.anchorCos());
                String from = run.embeddingProvider;
                if (from != null && !from.equals(s.embeddingProvider())) {
                    // The whole run was re-embedded with the next provider (SPEC §4.3).
                    Map<String, Object> params = new LinkedHashMap<>();
                    params.put("from", from);
                    params.put("to", s.embeddingProvider());
                    params.put("roundId", roundId);
                    alert("EMBEDDING_FAILOVER", city.code, params);
                }
                run.embeddingProvider = s.embeddingProvider();
                run.anchorCos = new ArrayList<>(s.anchorCos());
                run.floor = floor;
                for (Entry e : run.entries.values()) {
                    Double cos = s.entryCos().get(e.attemptId);
                    e.cos = cos;
                    e.score = cos == null ? null : ScoreCalculator.score(cos, floor);
                }
                List<CommentaryJob> jobs = settings.miniPodiumEnabled()
                        ? commentaryJobs(city, Ranking.roundCandidates(city, roundId), MINI_PODIUM_SIZE, null)
                        : List.of();
                if (jobs.isEmpty() && pendingCommentary(city, miniPrefix(roundId)) == 0) {
                    markScored(city, run);
                }
            }
            case ScoringFailed failed -> scoringFailed(city, run);
        }
    }

    private void markScored(CityRun city, RoundRun run) {
        run.status = RunStatus.SCORED;
        if (city.phase == Phase.ROUND_CLOSING && run.roundId.equals(city.currentRoundId)) {
            city.phase = Phase.ROUND_SCORED;
        }
        snapshot();
    }

    public void rescore(String cityCode, String roundId) {
        CityRun city = city(cityCode);
        RoundDefinition d = round(roundId);
        RoundRun run = city.runs.get(roundId);
        if (run == null || run.status != RunStatus.SCORING_FAILED) {
            throw new GameException(ErrorCode.SCORING_FAILED, "Run is not in SCORING_FAILED");
        }
        run.status = RunStatus.SCORING;
        requestScoring(city, run, d);
    }

    // ------------------------------------------------------------------ commentary

    static String miniPrefix(String roundId) {
        return "mini:" + roundId + ":";
    }

    static String finalPrefix() {
        return "final:";
    }

    static String commentaryKey(Reveal r, String playerId) {
        return r.kind == RevealKind.MINI_PODIUM ? miniPrefix(r.roundId) + playerId : finalPrefix() + playerId;
    }

    /**
     * Requests commentary for the top {@code slots} unrevealed candidates that have none yet.
     *
     * @param reveal null before the reveal exists (mini-podium right after scoring)
     */
    private List<CommentaryJob> commentaryJobs(CityRun city, List<Candidate> candidates, int slots, Reveal reveal) {
        List<CommentaryJob> jobs = new ArrayList<>();
        int rank = 0;
        int revealedCount = reveal == null ? 0 : reveal.revealed.size();
        for (Candidate c : candidates) {
            if (reveal != null && reveal.isRevealed(c.playerId())) {
                continue;
            }
            rank++;
            if (rank > slots) {
                break;
            }
            String key = c.total() == null ? miniPrefix(c.roundId()) + c.playerId() : finalPrefix() + c.playerId();
            if (city.commentaryRequested.contains(key) || city.commentary.containsKey(key)) {
                continue;
            }
            city.commentaryRequested.add(key);
            RoundDefinition d = findRound(c.roundId());
            Attempt a = city.runs.get(c.roundId()).attempts.get(c.attemptId());
            jobs.add(new CommentaryJob(key, d.targetImageId, a.imageId, c.roundScore(), rank, c.total()));
        }
        if (!jobs.isEmpty()) {
            emit(new Effect.WriteCommentary(city.code, jobs));
        }
        return jobs;
    }

    private static int pendingCommentary(CityRun city, String prefix) {
        int pending = 0;
        for (String key : city.commentaryRequested) {
            if (key.startsWith(prefix) && !city.commentary.containsKey(key)) {
                pending++;
            }
        }
        return pending;
    }

    public void commentaryFinished(String cityCode, Map<String, Commentary> results) {
        commentaryFinished(cityCode, results, 0);
    }

    /**
     * Stores finished comments. {@code fallbacks} counts comments a live model failed to write (canned instead);
     * they raise {@code COMMENTARY_FALLBACK} for the city's admins.
     */
    public void commentaryFinished(String cityCode, Map<String, Commentary> results, int fallbacks) {
        CityRun city = state.cities.get(cityCode);
        if (city == null) {
            return;
        }
        if (fallbacks > 0) {
            alert("COMMENTARY_FALLBACK", cityCode, Map.of("count", fallbacks));
        }
        for (Map.Entry<String, Commentary> e : results.entrySet()) {
            // putIfAbsent: a step revealed before its comment arrived already shows a canned one, and the text
            // on the projector never changes after the reveal.
            if (city.commentaryRequested.contains(e.getKey())) {
                city.commentary.putIfAbsent(e.getKey(), e.getValue());
            }
        }
        RoundRun run = city.currentRun();
        if (run != null && run.status == RunStatus.SCORING && run.floor != null
                && pendingCommentary(city, miniPrefix(run.roundId)) == 0) {
            markScored(city, run);
        }
        if (city.phase == Phase.FINAL_SCORING && pendingCommentary(city, finalPrefix()) == 0) {
            city.phase = Phase.PODIUM_REVEAL;
            snapshot();
        }
    }

    // ------------------------------------------------------------------ reveals

    public void startMiniPodium(String cityCode) {
        CityRun city = city(cityCode);
        if (!settings.miniPodiumEnabled() || city.phase != Phase.ROUND_SCORED) {
            throw GameException.invalidTransition(city.phase, "mini-podium/start");
        }
        RoundRun run = city.currentRun();
        List<Candidate> candidates = Ranking.roundCandidates(city, run.roundId);
        Reveal r = new Reveal();
        r.kind = RevealKind.MINI_PODIUM;
        r.roundId = run.roundId;
        r.size = Math.min(MINI_PODIUM_SIZE, candidates.size());
        r.startedAt = now();
        city.miniPodium = r;
        city.phase = Phase.MINI_PODIUM;
        commentaryJobs(city, candidates, r.size, r);
        snapshot();
    }

    public void nextMiniPodium(String cityCode) {
        CityRun city = city(cityCode);
        if (city.phase != Phase.MINI_PODIUM) {
            throw GameException.invalidTransition(city.phase, "mini-podium/next");
        }
        Reveal r = city.miniPodium;
        if (!revealNext(city, r, Ranking.roundCandidates(city, r.roundId))) {
            throw GameException.invalidTransition(city.phase, "mini-podium/next (all revealed)");
        }
    }

    public void startPodium(String cityCode) {
        CityRun city = city(cityCode);
        if (!START_PHASES.contains(city.phase) || Ranking.scoredRounds(state, city).isEmpty()) {
            throw GameException.invalidTransition(city.phase, "podium/start");
        }
        List<Candidate> candidates = Ranking.finalCandidates(state, city);
        Reveal r = new Reveal();
        r.kind = RevealKind.PODIUM;
        r.size = Math.min(PODIUM_SIZE, candidates.size());
        r.startedAt = now();
        city.podium = r;
        city.phase = Phase.FINAL_SCORING;
        commentaryJobs(city, candidates, r.size, r);
        if (pendingCommentary(city, finalPrefix()) == 0) {
            city.phase = Phase.PODIUM_REVEAL;
        }
        snapshot();
    }

    public void nextPodium(String cityCode) {
        CityRun city = city(cityCode);
        if (city.phase != Phase.PODIUM_REVEAL) {
            throw GameException.invalidTransition(city.phase, "podium/next");
        }
        if (!revealNext(city, city.podium, Ranking.finalCandidates(state, city))) {
            city.phase = Phase.FINISHED;
        }
        snapshot();
    }

    public void resetPodium(String cityCode) {
        CityRun city = city(cityCode);
        if (city.phase != Phase.PODIUM_REVEAL && city.phase != Phase.FINISHED) {
            throw GameException.invalidTransition(city.phase, "podium/reset");
        }
        Reveal r = city.podium;
        List<Candidate> candidates = Ranking.finalCandidates(state, city);
        r.revealed.clear();
        r.step = 0;
        r.size = Math.min(PODIUM_SIZE, candidates.size());
        city.phase = Phase.PODIUM_REVEAL;
        commentaryJobs(city, candidates, r.size, r);
        snapshot();
    }

    /**
     * Reveals the next step (rank size − step). Unrevealed ranks follow the current ranking, so a hide
     * before the reveal is taken into account; revealed steps never change. Returns false when done.
     */
    private boolean revealNext(CityRun city, Reveal r, List<Candidate> candidates) {
        List<Candidate> remaining = unrevealed(r, candidates);
        int slots = r.size - r.step;
        if (remaining.size() < slots) {
            r.size = r.step + remaining.size();
            slots = remaining.size();
        }
        if (slots <= 0) {
            return false;
        }
        Candidate pick = remaining.get(slots - 1);
        Revealed rv = new Revealed();
        rv.rank = slots;
        rv.playerId = pick.playerId();
        rv.attemptId = pick.attemptId();
        rv.roundId = pick.roundId();
        rv.roundScore = pick.roundScore();
        rv.total = pick.total();
        rv.commentaryKey = commentaryKey(r, pick.playerId());
        if (!city.commentary.containsKey(rv.commentaryKey)) {
            // The reveal never waits on an AI call.
            city.commentaryRequested.add(rv.commentaryKey);
            city.commentary.put(rv.commentaryKey, canned.canned(rv.commentaryKey, pick.roundScore()));
        }
        r.revealed.add(rv);
        r.step++;
        return true;
    }

    static List<Candidate> unrevealed(Reveal r, List<Candidate> candidates) {
        List<Candidate> remaining = new ArrayList<>();
        for (Candidate c : candidates) {
            if (!r.isRevealed(c.playerId())) {
                remaining.add(c);
            }
        }
        return remaining;
    }

    /** After a hide or kick: request commentary for candidates that moved into an unrevealed slot. */
    private void refreshCommentary(CityRun city) {
        RoundRun run = city.currentRun();
        if (settings.miniPodiumEnabled() && run != null && run.status == RunStatus.SCORED
                && (city.phase == Phase.ROUND_SCORED || city.phase == Phase.MINI_PODIUM)) {
            Reveal mini = city.phase == Phase.MINI_PODIUM ? city.miniPodium : null;
            int slots = mini == null ? MINI_PODIUM_SIZE : mini.size - mini.step;
            commentaryJobs(city, Ranking.roundCandidates(city, run.roundId), slots, mini);
        }
        if (city.phase == Phase.FINAL_SCORING || city.phase == Phase.PODIUM_REVEAL) {
            Reveal r = city.podium;
            commentaryJobs(city, Ranking.finalCandidates(state, city), r.size - r.step, r);
        }
    }

    public void showPracticeGallery(String cityCode, boolean show) {
        CityRun city = city(cityCode);
        RoundRun practice = null;
        for (RoundRun run : city.runs.values()) {
            RoundDefinition d = findRound(run.roundId);
            if (d != null && !d.scored && run.status == RunStatus.CLOSED
                    && (practice == null || run.closedAt.isAfter(practice.closedAt))) {
                practice = run;
            }
        }
        if (city.phase != Phase.LOBBY || practice == null) {
            throw GameException.invalidTransition(city.phase, "practice/gallery");
        }
        if (!show) {
            city.practiceGallery = null;
            return;
        }
        List<String> pool = new ArrayList<>();
        for (Entry e : practice.entries.values()) {
            Player p = city.players.get(e.playerId);
            if (!e.hidden && p != null && !p.kicked) {
                pool.add(e.attemptId);
            }
        }
        Collections.shuffle(pool, random);
        city.practiceGallery = new ArrayList<>(pool.subList(0, Math.min(GALLERY_SIZE, pool.size())));
    }

    // ------------------------------------------------------------------ players and entries (own city)

    public Views.AdminPlayer renamePlayer(String adminCity, String playerId, String rawNickname) {
        Player p = ownPlayer(adminCity, playerId);
        CityRun city = city(p.city);
        String nickname = NicknameRules.validate(rawNickname);
        if (moderation.blocksNickname(nickname)) {
            throw new GameException(ErrorCode.NICKNAME_BLOCKED, "Nickname blocked");
        }
        String key = NicknameRules.key(nickname);
        ensureNicknameFree(city, key, p.id);
        p.nickname = nickname;
        p.nicknameKey = key;
        return views.adminPlayer(city, p);
    }

    public void kickPlayer(String adminCity, String playerId) {
        Player p = ownPlayer(adminCity, playerId);
        p.kicked = true;
        refreshCommentary(city(p.city));
    }

    public void hideEntry(String adminCity, String entryId, boolean hidden) {
        for (CityRun city : state.cities.values()) {
            for (RoundRun run : city.runs.values()) {
                for (Entry e : run.entries.values()) {
                    if (e.attemptId.equals(entryId)) {
                        if (!city.code.equals(adminCity)) {
                            throw new GameException(ErrorCode.ADMIN_FORBIDDEN, "Entry belongs to another city");
                        }
                        e.hidden = hidden;
                        Located loc = findAttempt(entryId);
                        if (loc != null) {
                            loc.attempt().hidden = hidden;
                        }
                        refreshCommentary(city);
                        return;
                    }
                }
            }
        }
        throw new GameException(ErrorCode.ENTRY_NOT_FOUND, "Unknown entry " + entryId);
    }

    // ------------------------------------------------------------------ image providers (event-wide, SPEC §5.4)

    /** True when some configured image provider's circuit is not open (else PROVIDER_UNAVAILABLE). */
    public boolean imageProviderAvailable() {
        for (String p : settings.imageProviders()) {
            if (health.circuit(p) != CircuitState.OPEN) {
                return true;
            }
        }
        return false;
    }

    /**
     * The provider for a pinned job (target preview, calibration anchors): the active one unless its circuit is
     * open, else the first chain provider whose circuit isn't; null when every circuit is open.
     */
    public String pinnedImageProvider() {
        String active = state.activeImageProvider;
        if (active != null && health.circuit(active) != CircuitState.OPEN) {
            return active;
        }
        for (String p : settings.imageProviders()) {
            if (health.circuit(p) != CircuitState.OPEN) {
                return p;
            }
        }
        return null;
    }

    /** First configured provider in chain order whose circuit is closed, other than {@code except}. */
    private String firstClosedProvider(String except) {
        for (String p : settings.imageProviders()) {
            if (!p.equals(except) && health.circuit(p) == CircuitState.CLOSED) {
                return p;
            }
        }
        return null;
    }

    /**
     * A circuit opened (3 failures in 30 s). If it was the active provider, every open run switches to the
     * next healthy provider of the chain. Without a healthy one the active provider stays, and queued
     * generations wait for a half-open trial.
     */
    public void imageCircuitOpened(String providerId) {
        alert("CIRCUIT_OPEN", null, Map.of("providerId", providerId));
        // The event arrives asynchronously: if the circuit already closed again (a forced switch or an event
        // reset in between), switching now would undo that.
        if (providerId.equals(state.activeImageProvider) && health.circuit(providerId) != CircuitState.CLOSED) {
            String next = firstClosedProvider(providerId);
            if (next != null) {
                switchImageProvider(next, false);
                alert("PROVIDER_SWITCHED", null, switched(providerId, next));
            }
        }
    }

    /**
     * A circuit closed again. The active provider changes only if it is itself unhealthy: there is no
     * automatic fail-back (D-023); a recovered provider takes load-split overflow, and an admin can force it.
     */
    public void imageCircuitClosed(String providerId) {
        alert("CIRCUIT_CLOSED", null, Map.of("providerId", providerId));
        String active = state.activeImageProvider;
        if (active == null || health.circuit(active) != CircuitState.CLOSED) {
            String next = firstClosedProvider(null);
            if (next != null && !next.equals(active)) {
                switchImageProvider(next, false);
                alert("PROVIDER_SWITCHED", null, switched(active, next));
            }
        }
    }

    /** Either admin forces the active provider; it also closes that provider's circuit (manual override). */
    public void forceImageProvider(String providerId, String byCity) {
        if (providerId == null || !settings.imageProviders().contains(providerId)) {
            throw new GameException(ErrorCode.PROVIDER_NOT_FOUND, "Unknown or unconfigured image provider",
                    Map.of("providerId", String.valueOf(providerId)));
        }
        switchImageProvider(providerId, true);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("providerId", providerId);
        params.put("byCity", byCity);
        alert("PROVIDER_FORCED", null, params);
    }

    private static Map<String, Object> switched(String from, String to) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("from", from);
        params.put("to", to);
        return params;
    }

    /** The active provider is event-wide: every open (or closing) run of every city follows it. */
    private void switchImageProvider(String to, boolean resetCircuit) {
        state.activeImageProvider = to;
        for (CityRun city : state.cities.values()) {
            RoundRun run = city.currentRun();
            if (run != null && (run.status == RunStatus.OPEN || run.status == RunStatus.CLOSING)) {
                run.imageProvider = to;
            }
        }
        emit(new Effect.ActiveImageProvider(to, resetCircuit));
        snapshot();
    }

    /** Hands the persisted provider state to the adapters (startup and restore). */
    public void publishProviderState() {
        if (state.activeImageProvider != null) {
            emit(new Effect.ActiveImageProvider(state.activeImageProvider, false));
        }
        state.embeddingProviders.forEach((id, s) -> emit(new Effect.EmbeddingAveraging(id, s.averaging)));
    }

    // ------------------------------------------------------------------ embedding providers (event-wide)

    private EmbeddingProviderState embeddingState(String providerId) {
        return state.embeddingProviders.computeIfAbsent(providerId, k -> new EmbeddingProviderState());
    }

    /**
     * Determinism probe result (SPEC §4.2): with {@code averaging}, scoring averages two embedding calls per
     * image. The latest probe decides; the switch raises DETERMINISM_AVERAGING when it turns on.
     */
    public void determinismChecked(String providerId, double maxDiff, boolean averaging) {
        EmbeddingProviderState s = embeddingState(providerId);
        boolean turnedOn = averaging && !s.averaging;
        s.maxDiff = maxDiff;
        s.averaging = averaging;
        s.checkedAt = now();
        if (turnedOn) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("providerId", providerId);
            params.put("maxDiff", maxDiff);
            alert("DETERMINISM_AVERAGING", null, params);
        }
        emit(new Effect.EmbeddingAveraging(providerId, averaging));
    }

    /**
     * Client-side daily request count of a rate-limited embedding provider (D-013), kept in the snapshot.
     * Raises EMBEDDING_QUOTA_LOW once a day at 80 % of the limit.
     */
    public void embeddingUsage(String providerId, String day, int used, int limit) {
        EmbeddingProviderState s = embeddingState(providerId);
        if (!day.equals(s.usageDay)) {
            s.usageDay = day;
            s.usedToday = used;
        } else {
            s.usedToday = Math.max(s.usedToday, used); // reports from parallel calls may arrive out of order
        }
        if (limit > 0 && (long) s.usedToday * 5 >= (long) limit * 4 && !day.equals(s.quotaAlertDay)) {
            s.quotaAlertDay = day;
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("used", s.usedToday);
            params.put("limit", limit);
            alert("EMBEDDING_QUOTA_LOW", null, params);
        }
    }

    // ------------------------------------------------------------------ shared controls

    public void resetEvent(String confirm) {
        if (!"RESET".equals(confirm)) {
            throw new GameException(ErrorCode.RESET_NOT_CONFIRMED, "Reset not confirmed");
        }
        state.rounds.clear();
        state.previews.clear();
        state.alerts.clear();
        // Provider state survives the reset (D-023), but the quota alert it just deleted must be able to fire again.
        state.embeddingProviders.values().forEach(s -> s.quotaAlertDay = null);
        state.nextRoundPosition = 1;
        for (Map.Entry<String, CityRun> e : state.cities.entrySet()) {
            e.setValue(new CityRun(e.getKey(), e.getValue().name));
        }
        emit(new Effect.ResetStorage());
    }

    void alert(String code, String city, Map<String, Object> params) {
        Alert a = new Alert();
        a.id = UUID.randomUUID().toString();
        a.at = now();
        a.code = code;
        a.city = city;
        a.params = new LinkedHashMap<>(params);
        state.alerts.add(a);
        if (state.alerts.size() > MAX_ALERTS) {
            state.alerts.removeFirst();
        }
    }

    // ------------------------------------------------------------------ timers and restore

    /** Server-side timers: closes runs at {@code endsAt} and ends grace periods. Idempotent. */
    public void tick() {
        Instant now = now();
        for (CityRun city : state.cities.values()) {
            RoundRun run = city.currentRun();
            if (run == null) {
                continue;
            }
            if (city.phase == Phase.ROUND_OPEN && run.status == RunStatus.OPEN && !now.isBefore(run.endsAt)) {
                close(city, run);
            } else if (city.phase == Phase.ROUND_CLOSING && run.status == RunStatus.CLOSING
                    && !now.isBefore(run.graceEndsAt)) {
                finishClosing(city, run);
            }
        }
    }

    /**
     * After a restore: generations in flight were lost (they fail without using an attempt), pending
     * calibration, scoring and commentary are requested again.
     */
    public void resumeAfterRestore() {
        Instant now = now();
        for (RoundDefinition d : state.rounds) {
            if (d.calibration.status == CalibrationStatus.PENDING) {
                d.calibration.status = CalibrationStatus.FAILED;
                restartCalibration(d);
            }
        }
        for (CityRun city : state.cities.values()) {
            for (RoundRun run : city.runs.values()) {
                for (Attempt a : run.attempts.values()) {
                    if (a.status.inFlight()) {
                        a.status = AttemptStatus.FAILED;
                        a.code = ErrorCode.GENERATION_FAILED;
                        a.failureKind = FailureKind.PROVIDER_ERROR;
                        a.finishedAt = now;
                    }
                }
                if (run.status == RunStatus.CLOSING && run.inFlight() == 0) {
                    finishClosing(city, run);
                } else if (run.status == RunStatus.SCORING) {
                    if (run.floor == null) {
                        requestScoring(city, run, round(run.roundId));
                    } else {
                        city.commentaryRequested.removeIf(k -> k.startsWith(miniPrefix(run.roundId))
                                && !city.commentary.containsKey(k));
                        List<CommentaryJob> jobs = commentaryJobs(city, Ranking.roundCandidates(city, run.roundId),
                                MINI_PODIUM_SIZE, null);
                        if (jobs.isEmpty()) {
                            markScored(city, run);
                        }
                    }
                }
            }
            if (city.phase == Phase.FINAL_SCORING) {
                city.commentaryRequested.removeIf(k -> k.startsWith(finalPrefix()) && !city.commentary.containsKey(k));
                Reveal r = city.podium;
                commentaryJobs(city, Ranking.finalCandidates(state, city), r.size - r.step, r);
                if (pendingCommentary(city, finalPrefix()) == 0) {
                    city.phase = Phase.PODIUM_REVEAL;
                }
            }
        }
    }
}
