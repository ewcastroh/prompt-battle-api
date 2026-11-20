package io.github.ewcastroh.promptbattle.game;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Per-city ranking (SPEC §3.4, API.md §4). Pure functions over the state. */
final class Ranking {

    private Ranking() {
    }

    /**
     * A player's standing over the city's scored rounds.
     *
     * @param bestRoundId   round of the best entry (D-015: highest score, tie → earlier round)
     * @param counted       non-hidden scored entries; 0 = not eligible
     */
    record Standing(Player player, long totalTenths, long bestTenths, Instant lastSelectedAt,
            String bestRoundId, Entry bestEntry, int counted) {

        double total() {
            return totalTenths / 10.0;
        }

        double best() {
            return bestTenths / 10.0;
        }
    }

    /** A podium candidate, in rank order. {@code total} is null for a mini-podium. */
    record Candidate(String playerId, String attemptId, String roundId, double roundScore, Double total) {
    }

    private static final Comparator<Standing> FINAL_ORDER = Comparator
            .comparingLong(Standing::totalTenths).reversed()
            .thenComparing(Comparator.comparingLong(Standing::bestTenths).reversed())
            .thenComparing(Standing::lastSelectedAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(s -> s.player().joinedAt)
            .thenComparing(s -> s.player().id);

    /** Scored round definitions with a SCORED run in this city, in position order. */
    static List<RoundDefinition> scoredRounds(EventState state, CityRun city) {
        List<RoundDefinition> result = new ArrayList<>();
        for (RoundDefinition d : state.rounds) {
            RoundRun run = city.runs.get(d.id);
            if (d.scored && run != null && run.status == RunStatus.SCORED) {
                result.add(d);
            }
        }
        result.sort(Comparator.comparingInt(d -> d.position));
        return result;
    }

    /** Standings of every non-kicked player (eligible or not), unsorted. */
    static List<Standing> allStandings(EventState state, CityRun city) {
        List<RoundDefinition> rounds = scoredRounds(state, city);
        List<Standing> result = new ArrayList<>();
        for (Player p : city.players.values()) {
            if (!p.kicked) {
                result.add(standing(city, rounds, p));
            }
        }
        return result;
    }

    static Standing standing(CityRun city, List<RoundDefinition> scoredRounds, Player p) {
        long total = 0;
        long best = -1;
        Instant last = null;
        String bestRound = null;
        Entry bestEntry = null;
        int counted = 0;
        for (RoundDefinition d : scoredRounds) {
            Entry e = city.runs.get(d.id).entries.get(p.id);
            if (e == null || e.hidden || e.score == null) {
                continue;
            }
            long tenths = Math.round(e.score * 10);
            total += tenths;
            counted++;
            if (tenths > best) {
                best = tenths;
                bestRound = d.id;
                bestEntry = e;
            }
            if (last == null || e.selectedAt.isAfter(last)) {
                last = e.selectedAt;
            }
        }
        return new Standing(p, total, Math.max(best, 0), last, bestRound, bestEntry, counted);
    }

    /** Eligible players in final rank order: total desc → best round desc → earliest last selectedAt. */
    static List<Standing> finalRanking(EventState state, CityRun city) {
        List<Standing> result = new ArrayList<>();
        for (Standing s : allStandings(state, city)) {
            if (s.counted() > 0) {
                result.add(s);
            }
        }
        result.sort(FINAL_ORDER);
        return result;
    }

    static List<Candidate> finalCandidates(EventState state, CityRun city) {
        List<Candidate> result = new ArrayList<>();
        for (Standing s : finalRanking(state, city)) {
            result.add(new Candidate(s.player().id, s.bestEntry().attemptId, s.bestRoundId(),
                    s.bestEntry().score, s.total()));
        }
        return result;
    }

    /** Eligible entries of one round in rank order: score desc → earliest selectedAt. */
    static List<Candidate> roundCandidates(CityRun city, String roundId) {
        RoundRun run = city.runs.get(roundId);
        if (run == null) {
            return List.of();
        }
        List<Entry> entries = new ArrayList<>();
        for (Entry e : run.entries.values()) {
            Player p = city.players.get(e.playerId);
            if (p != null && !p.kicked && !e.hidden && e.score != null) {
                entries.add(e);
            }
        }
        entries.sort(Comparator.comparingDouble((Entry e) -> e.score).reversed()
                .thenComparing(e -> e.selectedAt)
                .thenComparing(e -> city.players.get(e.playerId).joinedAt)
                .thenComparing(e -> e.playerId));
        List<Candidate> result = new ArrayList<>();
        for (Entry e : entries) {
            result.add(new Candidate(e.playerId, e.attemptId, roundId, e.score, null));
        }
        return result;
    }
}
