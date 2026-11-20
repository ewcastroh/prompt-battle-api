package io.github.ewcastroh.promptbattle.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.github.ewcastroh.promptbattle.ApiClient;
import io.github.ewcastroh.promptbattle.RoundPlay;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/**
 * API.md §7 {@code GET /api/admin/results.csv}: own city only, a {@code city} column, one column per scored
 * round played in the city, ranked players first, no prompts, RFC 4180 quoting and no formula injection.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ResultsCsvTest {

    @Autowired
    MockMvc mvc;
    ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc);
        api.resetEvent();
    }

    @Test
    void ownCityResultsWithOneColumnPerScoredRound() {
        List<RoundPlay.Player> mde = List.of(join("mde", "Ana_dev"), join("mde", "Pixel_Paisa"),
                join("mde", "Hidden_Hank"), join("mde", "Lurker"), join("mde", "Kicked_One"));
        String r1 = RoundPlay.scoredRound(api, "mde", "Chiva, \"lluvia\"");
        String r2 = RoundPlay.scoredRound(api, "mde", "=SUM(A1)");
        String practice = api.createRound("mde", "Práctica", "a practice target", false).get("id").asString();

        List<String> e1 = RoundPlay.play(api, "mde", r1, mde, 88.0, 83.3, 95.0, null, 70.0);
        api.adminPost("mde", "/api/admin/entries/" + e1.get(2) + "/hide", Map.of("hidden", true), 204);
        api.adminPost("mde", "/api/admin/players/" + playerId("mde", "Kicked_One") + "/kick", null, 204);
        RoundPlay.play(api, "mde", r2, mde.subList(0, 2), 83.3, 90.0);

        // A practice round never gets a column.
        api.adminPost("mde", "/api/admin/rounds/" + practice + "/start");
        api.adminPost("mde", "/api/admin/rounds/" + practice + "/close");
        api.awaitPhase("mde", "LOBBY");

        // The other city: its own players only, and no column for rounds it hasn't played.
        join("bog", "Rolo_Dev");

        MockHttpServletResponse response = csv("mde");
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).isEqualTo("text/csv;charset=UTF-8");
        assertThat(response.getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"results-mde.csv\"");
        assertThat(ApiClient.body(response)).isEqualTo(String.join("\r\n",
                "city,rank,nickname,claimCode,total,\"Chiva, \"\"lluvia\"\"\",'=SUM(A1),kicked",
                "mde,1,Pixel_Paisa," + code("mde", "Pixel_Paisa") + ",173.3,83.3,90.0,false",
                "mde,2,Ana_dev," + code("mde", "Ana_dev") + ",171.3,88.0,83.3,false",
                "mde,,Hidden_Hank," + code("mde", "Hidden_Hank") + ",0.0,0.0,,false",
                "mde,,Lurker," + code("mde", "Lurker") + ",0.0,,,false",
                "mde,,Kicked_One," + code("mde", "Kicked_One") + ",70.0,70.0,,true",
                ""));
        assertThat(ApiClient.body(response)).doesNotContain("[sim=").doesNotContain("entry ");

        MockHttpServletResponse bog = csv("bog");
        assertThat(bog.getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"results-bog.csv\"");
        assertThat(ApiClient.body(bog)).isEqualTo("city,rank,nickname,claimCode,total,kicked\r\n"
                + "bog,,Rolo_Dev," + code("bog", "Rolo_Dev") + ",0.0,false\r\n");
    }

    @Test
    void needsAnAdminToken() {
        MockHttpServletResponse response = api.perform(get("/api/admin/results.csv"));
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void textCellsAreQuotedAndNeutralized() {
        assertThat(ResultsCsvController.text("plain")).isEqualTo("plain");
        assertThat(ResultsCsvController.text("a,b")).isEqualTo("\"a,b\"");
        assertThat(ResultsCsvController.text("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(ResultsCsvController.text("line\nbreak")).isEqualTo("\"line\nbreak\"");
        for (String formula : List.of("=1+1", "+1", "-1", "@SUM(A1)")) {
            assertThat(ResultsCsvController.text(formula)).isEqualTo("'" + formula);
        }
        assertThat(ResultsCsvController.text("=A1,B1")).isEqualTo("\"'=A1,B1\"");
    }

    private RoundPlay.Player join(String city, String nickname) {
        return new RoundPlay.Player(nickname, api.join(city, nickname).get("token").asString());
    }

    private MockHttpServletResponse csv(String city) {
        return api.perform(api.admin(get("/api/admin/results.csv"), city));
    }

    private JsonNode player(String city, String nickname) {
        for (JsonNode p : api.adminGet(city, "/api/admin/players")) {
            if (p.get("nickname").asString().equals(nickname)) {
                return p;
            }
        }
        throw new AssertionError(nickname);
    }

    private String playerId(String city, String nickname) {
        return player(city, nickname).get("id").asString();
    }

    private String code(String city, String nickname) {
        return player(city, nickname).get("claimCode").asString();
    }
}
