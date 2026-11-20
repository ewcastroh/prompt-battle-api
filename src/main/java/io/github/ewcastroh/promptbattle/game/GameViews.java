package io.github.ewcastroh.promptbattle.game;

import io.github.ewcastroh.promptbattle.game.Ranking.Candidate;
import io.github.ewcastroh.promptbattle.game.Ranking.Standing;
import io.github.ewcastroh.promptbattle.game.Reveal.Revealed;
import io.github.ewcastroh.promptbattle.game.Views.AdminEntry;
import io.github.ewcastroh.promptbattle.game.Views.AdminPlayer;
import io.github.ewcastroh.promptbattle.game.Views.AdminRound;
import io.github.ewcastroh.promptbattle.game.Views.AdminState;
import io.github.ewcastroh.promptbattle.game.Views.AlertView;
import io.github.ewcastroh.promptbattle.game.Views.AnchorView;
import io.github.ewcastroh.promptbattle.game.Views.AttemptView;
import io.github.ewcastroh.promptbattle.game.Views.CalibrationView;
import io.github.ewcastroh.promptbattle.game.Views.CityInfo;
import io.github.ewcastroh.promptbattle.game.Views.FinalResult;
import io.github.ewcastroh.promptbattle.game.Views.GalleryItem;
import io.github.ewcastroh.promptbattle.game.Views.HiddenPrompt;
import io.github.ewcastroh.promptbattle.game.Views.Me;
import io.github.ewcastroh.promptbattle.game.Views.MeRound;
import io.github.ewcastroh.promptbattle.game.Views.PlayerInfo;
import io.github.ewcastroh.promptbattle.game.Views.PreviewItem;
import io.github.ewcastroh.promptbattle.game.Views.RevealEntry;
import io.github.ewcastroh.promptbattle.game.Views.RevealPreview;
import io.github.ewcastroh.promptbattle.game.Views.RoundDefinitionView;
import io.github.ewcastroh.promptbattle.game.Views.RunView;
import io.github.ewcastroh.promptbattle.game.Views.Stage;
import io.github.ewcastroh.promptbattle.game.Views.StageReveal;
import io.github.ewcastroh.promptbattle.game.Views.StageRound;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read models. Pure reads of the state: no mutation, safe to call on the engine thread at any time. */
public final class GameViews {

    private static final Set<Phase> PLAYER_ROUND_PHASES = EnumSet.of(Phase.ROUND_OPEN, Phase.ROUND_CLOSING,
            Phase.ROUND_SCORED, Phase.MINI_PODIUM);
    private static final Set<Phase> STAGE_ROUND_PHASES = EnumSet.of(Phase.ROUND_OPEN, Phase.ROUND_CLOSING,
            Phase.ROUND_SCORED);

    private final Game game;
    private final EventState state;

    GameViews(Game game) {
        this.game = game;
        this.state = game.state;
    }

    public List<CityInfo> cities() {
        List<CityInfo> result = new ArrayList<>();
        for (CityRun c : state.cities.values()) {
            result.add(new CityInfo(c.code, c.name));
        }
        return result;
    }

    public CityInfo cityInfo(String code) {
        CityRun c = game.city(code);
        return new CityInfo(c.code, c.name);
    }

    // ------------------------------------------------------------------ player

    public Me me(String token) {
        Player p = game.authenticate(token);
        CityRun city = state.cities.get(p.city);
        PlayerInfo info = new PlayerInfo(p.id, p.nickname, p.claimCode, city.code, city.name);
        RoundRun run = city.currentRun();
        MeRound round = null;
        List<AttemptView> attempts = List.of();
        if (run != null && PLAYER_ROUND_PHASES.contains(city.phase)) {
            RoundDefinition d = game.round(run.roundId);
            Entry entry = run.entries.get(p.id);
            int left = Math.max(0, d.attemptsPerPlayer - run.doneCount(p.id));
            round = new MeRound(d.id, d.name, d.scored, Views.imageUrl(d.targetImageId),
                    Views.thumbUrl(d.targetImageId), run.startedAt, run.endsAt, d.attemptsPerPlayer, left,
                    entry == null ? null : entry.attemptId);
            attempts = new ArrayList<>();
            for (Attempt a : run.attemptsOf(p.id)) {
                attempts.add(attempt(a));
            }
        }
        return new Me(info, city.phase, round, attempts, finalResult(city, p));
    }

    public AttemptView attempt(String token, String attemptId) {
        Player p = game.authenticate(token);
        Game.Located loc = game.findAttempt(attemptId);
        if (loc == null || !loc.attempt().playerId.equals(p.id)) {
            throw new GameException(ErrorCode.ATTEMPT_NOT_FOUND, "Unknown attempt " + attemptId);
        }
        return attempt(loc.attempt());
    }

    private AttemptView attempt(Attempt a) {
        Integer position = a.status == AttemptStatus.QUEUED ? game.queue.positionOf(a.id) : null;
        return new AttemptView(a.id, a.status, a.prompt, position, Views.imageUrl(a.imageId),
                Views.thumbUrl(a.imageId), a.code, a.createdAt);
    }

    /** Shown once rank #1 is revealed and in FINISHED (API.md §4). */
    private FinalResult finalResult(CityRun city, Player p) {
        Reveal r = city.podium;
        boolean visible = r != null && (city.phase == Phase.FINISHED
                || (city.phase == Phase.PODIUM_REVEAL && r.step >= r.size));
        if (!visible) {
            return null;
        }
        for (Revealed rv : r.revealed) {
            if (rv.playerId.equals(p.id)) {
                return new FinalResult(rv.rank, rv.total, true);
            }
        }
        List<Standing> ranking = Ranking.finalRanking(state, city);
        int rank = r.revealed.size();
        for (Standing s : ranking) {
            if (r.isRevealed(s.player().id)) {
                continue;
            }
            rank++;
            if (s.player().id.equals(p.id)) {
                return new FinalResult(rank, s.total(), false);
            }
        }
        Standing own = Ranking.standing(city, Ranking.scoredRounds(state, city), p);
        return new FinalResult(null, own.total(), false);
    }

    // ------------------------------------------------------------------ stage

    public Stage stage(String cityCode) {
        CityRun city = game.city(cityCode);
        List<Player> active = city.activePlayers();
        List<String> nicknames = null;
        if (city.phase == Phase.LOBBY) {
            nicknames = new ArrayList<>();
            for (Player p : active) {
                nicknames.add(p.nickname);
            }
        }
        RoundRun run = city.currentRun();
        StageRound round = null;
        if (run != null && STAGE_ROUND_PHASES.contains(city.phase)) {
            RoundDefinition d = game.round(run.roundId);
            round = new StageRound(d.id, d.name, d.scored, Views.imageUrl(d.targetImageId), run.startedAt,
                    run.endsAt, run.entries.size(), doneAttempts(run), run.status);
        }
        List<GalleryItem> gallery = null;
        if (city.phase == Phase.LOBBY && city.practiceGallery != null) {
            gallery = new ArrayList<>();
            for (String attemptId : city.practiceGallery) {
                Game.Located loc = game.findAttempt(attemptId);
                if (loc == null) {
                    continue;
                }
                Entry e = loc.run().entries.get(loc.attempt().playerId);
                Player p = city.players.get(loc.attempt().playerId);
                if (e == null || e.hidden || !e.attemptId.equals(attemptId) || p.kicked) {
                    continue;
                }
                gallery.add(new GalleryItem(p.nickname, Views.imageUrl(loc.attempt().imageId),
                        Views.thumbUrl(loc.attempt().imageId)));
            }
        }
        StageReveal reveal = null;
        if (city.phase == Phase.MINI_PODIUM) {
            reveal = stageReveal(city, city.miniPodium);
        } else if (city.phase == Phase.PODIUM_REVEAL || city.phase == Phase.FINISHED) {
            reveal = stageReveal(city, city.podium);
        }
        List<HiddenPrompt> hiddenPrompts = null;
        if (city.phase == Phase.FINISHED) {
            hiddenPrompts = new ArrayList<>();
            for (RoundDefinition d : Ranking.scoredRounds(state, city)) {
                hiddenPrompts.add(new HiddenPrompt(d.id, d.name, d.hiddenPrompt));
            }
        }
        return new Stage(new CityInfo(city.code, city.name), city.phase,
                game.settings.publicUiUrl() + "/play/" + city.code, "/api/cities/" + city.code + "/qr.png",
                active.size(), nicknames, round, gallery, reveal, hiddenPrompts);
    }

    private static int doneAttempts(RoundRun run) {
        int n = 0;
        for (Attempt a : run.attempts.values()) {
            if (a.status == AttemptStatus.DONE) {
                n++;
            }
        }
        return n;
    }

    /** Only revealed steps, never hidden entries or kicked players. */
    private StageReveal stageReveal(CityRun city, Reveal r) {
        List<RevealEntry> entries = new ArrayList<>();
        for (Revealed rv : r.revealed) {
            Player p = city.players.get(rv.playerId);
            RoundRun run = city.runs.get(rv.roundId);
            Entry e = run.entries.get(rv.playerId);
            Attempt a = run.attempts.get(rv.attemptId);
            if (p.kicked || e == null || e.hidden || !e.attemptId.equals(rv.attemptId)) {
                continue;
            }
            RoundDefinition d = game.findRound(rv.roundId);
            entries.add(new RevealEntry(rv.rank, p.nickname, a.prompt, Views.imageUrl(a.imageId),
                    d == null ? null : Views.imageUrl(d.targetImageId), d == null ? null : d.name, rv.roundScore,
                    rv.total, city.commentary.get(rv.commentaryKey)));
        }
        return new StageReveal(r.kind, r.roundId, r.size, r.step, entries);
    }

    // ------------------------------------------------------------------ admin

    public AdminState adminState(String cityCode) {
        CityRun city = game.city(cityCode);
        RoundRun run = city.currentRun();
        AdminRound round = null;
        if (run != null) {
            RoundDefinition d = game.findRound(run.roundId);
            round = new AdminRound(run.roundId, d == null ? null : d.name, d != null && d.scored, run.status,
                    run.startedAt, run.endsAt, run.extensions, run.imageProvider, run.embeddingProvider,
                    run.entries.size(), run.inFlight());
        }
        RevealPreview mini = null;
        if (game.settings.miniPodiumEnabled() && run != null && run.status == RunStatus.SCORED) {
            if (city.phase == Phase.MINI_PODIUM) {
                mini = preview(city, city.miniPodium, Ranking.roundCandidates(city, run.roundId), false);
            } else if (city.phase == Phase.ROUND_SCORED) {
                mini = livePreview(city, Ranking.roundCandidates(city, run.roundId), Game.MINI_PODIUM_SIZE,
                        run.roundId);
            }
        }
        RevealPreview podium = null;
        List<Candidate> finalCandidates = Ranking.finalCandidates(state, city);
        if (city.podium != null && EnumSet.of(Phase.FINAL_SCORING, Phase.PODIUM_REVEAL, Phase.FINISHED)
                .contains(city.phase)) {
            podium = preview(city, city.podium, finalCandidates, true);
        } else if (!Ranking.scoredRounds(state, city).isEmpty()) {
            podium = livePreview(city, finalCandidates, Game.PODIUM_SIZE, null);
        }
        return new AdminState(city.code, city.phase, city.activePlayers().size(),
                Ranking.scoredRounds(state, city).size(), round, mini, podium, city.practiceGallery != null);
    }

    private RevealPreview livePreview(CityRun city, List<Candidate> candidates, int max, String roundId) {
        int size = Math.min(max, candidates.size());
        List<PreviewItem> items = new ArrayList<>();
        for (int rank = size; rank >= 1; rank--) {
            items.add(item(city, candidates.get(rank - 1), rank, false));
        }
        return new RevealPreview(roundId, size, null, items);
    }

    /** Revealed steps in reveal order, then the unrevealed ranks as they stand now. */
    private RevealPreview preview(CityRun city, Reveal r, List<Candidate> candidates, boolean podium) {
        List<PreviewItem> items = new ArrayList<>();
        for (Revealed rv : r.revealed) {
            Player p = city.players.get(rv.playerId);
            items.add(new PreviewItem(rv.rank, rv.playerId, p.nickname, rv.attemptId,
                    podium ? rv.total : rv.roundScore, true, city.commentary.containsKey(rv.commentaryKey)));
        }
        List<Candidate> remaining = Game.unrevealed(r, candidates);
        int slots = Math.min(r.size - r.step, remaining.size());
        for (int rank = slots; rank >= 1; rank--) {
            items.add(item(city, remaining.get(rank - 1), rank, false));
        }
        return new RevealPreview(podium ? null : r.roundId, r.size, r.step, items);
    }

    private static PreviewItem item(CityRun city, Candidate c, int rank, boolean revealed) {
        String key = c.total() == null ? Game.miniPrefix(c.roundId()) + c.playerId() : Game.finalPrefix() + c.playerId();
        return new PreviewItem(rank, c.playerId(), city.players.get(c.playerId()).nickname, c.attemptId(),
                c.total() == null ? c.roundScore() : c.total(), revealed, city.commentary.containsKey(key));
    }

    public List<RoundDefinitionView> roundDefinitions() {
        List<RoundDefinition> sorted = new ArrayList<>(state.rounds);
        sorted.sort(Comparator.comparingInt(d -> d.position));
        List<RoundDefinitionView> result = new ArrayList<>();
        for (RoundDefinition d : sorted) {
            result.add(roundDefinition(d));
        }
        return result;
    }

    public RoundDefinitionView roundDefinition(String roundId) {
        return roundDefinition(game.round(roundId));
    }

    RoundDefinitionView roundDefinition(RoundDefinition d) {
        List<RunView> runs = new ArrayList<>();
        RoundRun latestScored = null;
        for (CityRun city : state.cities.values()) {
            RoundRun run = city.runs.get(d.id);
            if (run == null) {
                runs.add(new RunView(city.code, RunStatus.NOT_STARTED, null, null, 0, null, null, null, false));
                continue;
            }
            runs.add(new RunView(city.code, run.status, run.startedAt, run.endsAt, run.extensions,
                    run.imageProvider, run.floor == null ? null : run.embeddingProvider, run.floor,
                    mixedProviders(run)));
            if (run.floor != null && (latestScored == null || run.closedAt.isAfter(latestScored.closedAt))) {
                latestScored = run;
            }
        }
        List<AnchorView> anchors = new ArrayList<>();
        for (int i = 0; i < d.calibration.anchors.size(); i++) {
            Calibration.Anchor a = d.calibration.anchors.get(i);
            boolean excluded = latestScored != null && i < latestScored.anchorCos.size()
                    && ScoreCalculator.excluded(latestScored.anchorCos.get(i));
            anchors.add(new AnchorView(a.prompt, Views.imageUrl(a.imageId), Views.thumbUrl(a.imageId), excluded));
        }
        return new RoundDefinitionView(d.id, d.position, d.name, d.scored, d.hiddenPrompt, d.durationSec,
                d.attemptsPerPlayer, d.seed, Views.imageUrl(d.targetImageId), Views.thumbUrl(d.targetImageId),
                d.targetSource, d.locked, new CalibrationView(d.calibration.status, d.calibration.provider, anchors),
                runs);
    }

    private static boolean mixedProviders(RoundRun run) {
        Set<String> providers = new HashSet<>();
        for (Attempt a : run.attempts.values()) {
            if (a.status == AttemptStatus.DONE && a.provider != null) {
                providers.add(a.provider);
            }
        }
        return providers.size() > 1;
    }

    static Views.PreviewView preview(Preview p) {
        return new Views.PreviewView(p.id, Views.imageUrl(p.imageId), Views.thumbUrl(p.imageId), p.seed, p.provider,
                p.model);
    }

    public List<AdminPlayer> players(String cityCode) {
        CityRun city = game.city(cityCode);
        List<AdminPlayer> result = new ArrayList<>();
        for (Player p : city.players.values()) {
            result.add(adminPlayer(city, p));
        }
        return result;
    }

    AdminPlayer adminPlayer(CityRun city, Player p) {
        int attempts = 0;
        for (RoundRun run : city.runs.values()) {
            attempts += run.attemptsOf(p.id).size();
        }
        RoundRun current = city.currentRun();
        int blocked = current == null ? 0 : current.blocks.getOrDefault(p.id, 0);
        double total = Ranking.standing(city, Ranking.scoredRounds(state, city), p).total();
        return new AdminPlayer(p.id, p.nickname, p.claimCode, p.joinedAt, p.kicked, p.flagged, blocked, attempts,
                total);
    }

    public List<AdminEntry> entries(String cityCode, String roundId) {
        CityRun city = game.city(cityCode);
        game.round(roundId);
        RoundRun run = city.runs.get(roundId);
        if (run == null) {
            return List.of();
        }
        List<AdminEntry> result = new ArrayList<>();
        for (Entry e : run.entries.values()) {
            Attempt a = run.attempts.get(e.attemptId);
            Player p = city.players.get(e.playerId);
            result.add(new AdminEntry(e.attemptId, p.id, p.nickname, a.prompt, null, Views.imageUrl(a.imageId),
                    Views.thumbUrl(a.imageId), a.provider, a.model, e.score == null ? null : run.embeddingProvider,
                    e.cos, e.score == null ? null : run.floor, e.score, e.hidden, e.auto, e.selectedAt));
        }
        result.sort(Comparator.comparing(AdminEntry::score, Comparator.nullsLast(Comparator.reverseOrder())));
        return result;
    }

    /** Newest first, at most 100. */
    public List<AlertView> alerts(Instant since) {
        List<AlertView> result = new ArrayList<>();
        for (int i = state.alerts.size() - 1; i >= 0 && result.size() < 100; i--) {
            Alert a = state.alerts.get(i);
            if (since == null || a.at.isAfter(since)) {
                result.add(new AlertView(a.id, a.at, a.code, a.city, Map.copyOf(a.params)));
            }
        }
        return result;
    }
}
