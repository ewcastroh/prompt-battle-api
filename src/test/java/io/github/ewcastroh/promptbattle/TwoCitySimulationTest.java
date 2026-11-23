package io.github.ewcastroh.promptbattle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import io.github.ewcastroh.promptbattle.generation.FakeImageGenerator;
import io.github.ewcastroh.promptbattle.generation.ProviderRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/**
 * SPEC §14.1-4 on the {@code fake} profile: 2 cities × 50 players, practice + 2 scored rounds, cities
 * starting rounds at different times, deterministic fake embeddings ({@code [sim=x]}) → the expected podium
 * order per city; {@code next} reveals exactly one more entry; the stage never exposes unrevealed entries
 * or the other city's data.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TwoCitySimulationTest {

    static final int PLAYERS = 50;

    @Autowired
    MockMvc mvc;
    @Autowired
    ProviderRegistry providers;
    ApiClient api;

    /** One simulated player and the score (in tenths) they should get per scored round (0 = no entry). */
    record SimPlayer(String city, int index, String nickname, String token, int[] tenths) {

        int total() {
            return tenths[0] + tenths[1];
        }

        int best() {
            return Math.max(tenths[0], tenths[1]);
        }
    }

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc);
        api.resetEvent(); // test classes share one cached context
    }

    private static int permutation(int i, int multiplier) {
        return (i * multiplier) % PLAYERS + 1; // bijection of 1..50 (multiplier coprime with 50)
    }

    private List<SimPlayer> joinCity(String city, int m1, int m2) {
        List<SimPlayer> players = new ArrayList<>();
        for (int i = 1; i <= PLAYERS; i++) {
            String nickname = String.format(Locale.ROOT, "%s_p%02d", city, i);
            String token = api.join(city, nickname).get("token").asString();
            int r1 = 300 + 7 * permutation(i, m1); // tenths: 30.7 … 65.0
            int r2 = i % 10 == 9 ? 0 : 200 + 3 * permutation(i, m2); // every 10th player skips round 2
            players.add(new SimPlayer(city, i, nickname, token, new int[] { r1, r2 }));
        }
        return players;
    }

    private static String sim(int tenths, String words) {
        return String.format(Locale.ROOT, "[sim=%.3f] %s", tenths / 1000.0, words);
    }

    /**
     * Plays one scored round for a city: entries chosen manually, automatically, or after a second attempt;
     * one player first hits a provider failure (no attempt used).
     */
    private void playScoredRound(String city, String roundId, List<SimPlayer> players, int round) {
        assertThat(api.adminPost(city, "/api/admin/rounds/" + roundId + "/start").get("phase").asString())
                .isEqualTo("ROUND_OPEN");
        String stageWhileOpen = api.stageText(city);
        JsonNode stage = api.stage(city);
        assertThat(stageWhileOpen.split("/img/", -1)).as("only the target image on the stage while open")
                .hasSize(2);
        assertThat(stage.get("round").get("targetUrl").asString()).startsWith("/img/");

        Map<SimPlayer, String> first = new LinkedHashMap<>();
        for (SimPlayer p : players) {
            int tenths = p.tenths()[round];
            if (tenths == 0) {
                continue;
            }
            if (p.index() == 7) {
                api.attempt(p.token(), "[fail] provider error");
                api.awaitIdle(city);
                // One deliberate failure per round and city: heal the single fake provider's circuit so they
                // don't add up to 3 within 30 s (FailoverTest covers the breaker itself).
                providers.reset(FakeImageGenerator.ID);
            }
            String prompt = switch (p.index() % 4) {
                case 0 -> sim(tenths, "chosen first try");
                case 1 -> "[sim=0.999] replaced by the second try";
                default -> sim(tenths, "single try");
            };
            first.put(p, api.attempt(p.token(), prompt));
        }
        api.awaitIdle(city);
        for (Map.Entry<SimPlayer, String> e : first.entrySet()) {
            SimPlayer p = e.getKey();
            switch (p.index() % 4) {
                case 0 -> api.attempt(p.token(), "[sim=0.990] not selected");
                case 1 -> api.attempt(p.token(), sim(p.tenths()[round], "auto entry, last success"));
                default -> {
                }
            }
        }
        api.awaitIdle(city);
        for (Map.Entry<SimPlayer, String> e : first.entrySet()) {
            int kind = e.getKey().index() % 4;
            if (kind == 0 || kind == 2) {
                api.select(e.getKey().token(), e.getValue());
            }
        }
        JsonNode me7 = api.me(players.get(6).token());
        assertThat(me7.get("round").get("attemptsLeft").asInt()).as("a provider failure uses no attempt")
                .isEqualTo(2);
        assertThat(me7.get("final").isNull()).isTrue();

        api.adminPost(city, "/api/admin/rounds/" + roundId + "/close");
        assertNothingRevealed(city, "while closing");
        api.awaitPhase(city, "ROUND_SCORED");
        assertNothingRevealed(city, "scored, before any reveal");

        // Every entry has exactly the expected score.
        JsonNode entries = api.adminGet(city, "/api/admin/entries?roundId=" + roundId);
        Map<String, Double> scores = new LinkedHashMap<>();
        entries.forEach(e -> scores.put(e.get("nickname").asString(), e.get("score").asDouble()));
        for (SimPlayer p : players) {
            if (p.tenths()[round] == 0) {
                assertThat(scores).doesNotContainKey(p.nickname());
            } else {
                assertThat(scores.get(p.nickname())).as(p.nickname()).isEqualTo(p.tenths()[round] / 10.0);
            }
        }
    }

    private static List<SimPlayer> expectedRoundTop(List<SimPlayer> players, int round, int n) {
        return players.stream().filter(p -> p.tenths()[round] > 0)
                .sorted(Comparator.comparingInt((SimPlayer p) -> p.tenths()[round]).reversed()).limit(n).toList();
    }

    private static List<SimPlayer> expectedPodium(List<SimPlayer> players) {
        List<SimPlayer> sorted = players.stream()
                .sorted(Comparator.comparingInt(SimPlayer::total).reversed()
                        .thenComparing(Comparator.comparingInt(SimPlayer::best).reversed()))
                .toList();
        for (int i = 0; i < 5; i++) {
            assertThat(sorted.get(i).total()).as("test data: top totals are distinct")
                    .isNotEqualTo(sorted.get(i + 1).total());
        }
        return sorted.subList(0, 5);
    }

    /** SPEC §14.1-4: scores may exist, but the stage shows no player's image or nickname until a reveal. */
    private void assertNothingRevealed(String city, String when) {
        String text = api.stageText(city);
        assertThat(text.split("/img/", -1)).as("at most the target image on the stage " + when)
                .hasSizeLessThanOrEqualTo(2);
        assertThat(text).as("no nickname on the stage " + when).doesNotContain(city + "_p");
        JsonNode reveal = api.stage(city).get("reveal");
        assertThat(reveal == null || reveal.isNull() || reveal.get("entries").isEmpty()).as(when).isTrue();
    }

    private void miniPodium(String city, List<SimPlayer> players, String other) {
        api.adminPost(city, "/api/admin/mini-podium/start");
        assertNothingRevealed(city, "at mini-podium step 0");
        List<SimPlayer> top = expectedRoundTop(players, 0, 3);
        for (int step = 1; step <= 3; step++) {
            JsonNode reveal = api.adminPost(city, "/api/admin/mini-podium/next").get("miniPodium");
            assertThat(reveal.get("step").asInt()).isEqualTo(step);
            JsonNode stage = api.stage(city).get("reveal");
            assertThat(stage.get("kind").asString()).isEqualTo("MINI_PODIUM");
            assertThat(stage.get("entries")).as("exactly one more entry per next").hasSize(step);
            JsonNode last = stage.get("entries").get(step - 1);
            SimPlayer expected = top.get(3 - step);
            assertThat(last.get("rank").asInt()).isEqualTo(4 - step);
            assertThat(last.get("nickname").asString()).isEqualTo(expected.nickname());
            assertThat(last.get("commentary").get("es").asString()).isNotBlank();
            assertThat(api.stageText(other)).doesNotContain(city + "_p");
        }
        api.call(api.admin(api.jsonPost("/api/admin/mini-podium/next", null), city), 409);
    }

    private void finalPodium(String city, List<SimPlayer> players, String other) {
        api.adminPost(city, "/api/admin/podium/start");
        api.awaitPhase(city, "PODIUM_REVEAL");
        List<SimPlayer> podium = expectedPodium(players);
        assertThat(api.stage(city).get("reveal").get("entries")).isEmpty();
        for (int step = 1; step <= 5; step++) {
            api.adminPost(city, "/api/admin/podium/next");
            JsonNode stage = api.stage(city);
            JsonNode entries = stage.get("reveal").get("entries");
            assertThat(entries).as("exactly one more entry per next").hasSize(step);
            JsonNode last = entries.get(step - 1);
            SimPlayer expected = podium.get(5 - step);
            assertThat(last.get("rank").asInt()).isEqualTo(6 - step);
            assertThat(last.get("nickname").asString()).isEqualTo(expected.nickname());
            assertThat(last.get("total").asDouble()).isEqualTo(expected.total() / 10.0);
            assertThat(last.get("roundScore").asDouble()).isEqualTo(expected.best() / 10.0);
            String text = api.stageText(city);
            for (SimPlayer notYet : podium.subList(0, 5 - step)) {
                assertThat(text).as("unrevealed entries never reach the stage").doesNotContain(notYet.nickname());
            }
            assertThat(api.stageText(other)).doesNotContain(city + "_p");
            boolean numberOneRevealed = step == 5;
            assertThat(api.me(podium.getFirst().token()).get("final").isNull()).isEqualTo(!numberOneRevealed);
        }
        JsonNode winner = api.me(podium.getFirst().token()).get("final");
        assertThat(winner.get("rank").asInt()).isEqualTo(1);
        assertThat(winner.get("podium").asBoolean()).isTrue();
        assertThat(winner.get("total").asDouble()).isEqualTo(podium.getFirst().total() / 10.0);

        api.adminPost(city, "/api/admin/podium/next");
        JsonNode finished = api.stage(city);
        assertThat(finished.get("phase").asString()).isEqualTo("FINISHED");
        assertThat(finished.get("hiddenPrompts")).hasSize(2);
        assertThat(finished.get("reveal").get("entries")).hasSize(5);
    }

    @Test
    void twoCitiesOfFiftyPlayersPlayAFullEvent() {
        JsonNode practice = api.createRound("mde", "Práctica", "a red apple on a white table", false);
        JsonNode r1 = api.createRound("bog", "Café al amanecer", "[target] a steaming cup of coffee at dawn", true);
        JsonNode r2 = api.createRound("mde", "Chiva en la lluvia", "[target] a colorful chiva bus in the rain", true);
        String practiceId = practice.get("id").asString();
        String r1Id = r1.get("id").asString();
        String r2Id = r2.get("id").asString();

        List<SimPlayer> mde = joinCity("mde", 17, 23);
        List<SimPlayer> bog = joinCity("bog", 29, 13);
        assertThat(api.stage("mde").get("nicknames")).hasSize(50);
        assertThat(api.stageText("mde")).doesNotContain("bog_p");
        assertThat(api.stageText("bog")).doesNotContain("mde_p");

        // Medellín plays the practice round while Bogotá is still in the lobby.
        api.adminPost("mde", "/api/admin/rounds/" + practiceId + "/start");
        assertThat(api.state("bog").get("phase").asString()).isEqualTo("LOBBY");
        for (SimPlayer p : mde) {
            api.attempt(p.token(), "a red apple");
        }
        api.awaitIdle("mde");
        api.adminPost("mde", "/api/admin/rounds/" + practiceId + "/close");
        api.awaitPhase("mde", "LOBBY");
        api.adminPost("mde", "/api/admin/practice/gallery", Map.of("show", true), 200);
        assertThat(api.stage("mde").get("practiceGallery")).hasSize(6);

        // Bogotá starts the practice later and closes it while Medellín plays round 1.
        api.adminPost("bog", "/api/admin/rounds/" + practiceId + "/start");
        for (SimPlayer p : bog.subList(0, 10)) {
            api.attempt(p.token(), "an apple");
        }
        assertThat(api.round("mde", practiceId).get("locked").asBoolean()).isTrue();
        playScoredRound("mde", r1Id, mde, 0);
        api.awaitIdle("bog");
        api.adminPost("bog", "/api/admin/rounds/" + practiceId + "/close");
        api.awaitPhase("bog", "LOBBY");

        // Medellín reveals its mini-podium while Bogotá plays round 1.
        miniPodium("mde", mde, "bog");
        playScoredRound("bog", r1Id, bog, 0);
        playScoredRound("mde", r2Id, mde, 1);
        miniPodium("bog", bog, "mde");
        playScoredRound("bog", r2Id, bog, 1);

        // Each city's final podium, revealed by its own admin.
        finalPodium("mde", mde, "bog");
        assertThat(api.state("bog").get("phase").asString()).isEqualTo("ROUND_SCORED");
        finalPodium("bog", bog, "mde");

        JsonNode runs = api.round("mde", r2Id).get("runs");
        assertThat(runs).extracting(r -> r.get("status").asString()).containsExactly("SCORED", "SCORED");
        assertThat(runs).extracting(r -> r.get("floor").asDouble()).containsExactly(0.0, 0.0);
    }
}
