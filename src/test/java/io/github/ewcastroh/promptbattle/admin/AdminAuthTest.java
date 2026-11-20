package io.github.ewcastroh.promptbattle.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.github.ewcastroh.promptbattle.ApiClient;
import io.github.ewcastroh.promptbattle.city.AdminTokens;
import io.github.ewcastroh.promptbattle.city.CityCatalog;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/** SPEC §6.4, §14.1-1: admin token → city, cross-city rejection, bad tokens, startup validation. */
@SpringBootTest
@AutoConfigureMockMvc
class AdminAuthTest {

    @Autowired
    MockMvc mvc;
    ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc);
        api.resetEvent(); // test classes share one cached context
    }

    @Test
    void tokenDecidesTheAdminsCity() {
        JsonNode mde = api.adminGet("mde", "/api/admin/me");
        assertThat(mde.get("city").asString()).isEqualTo("mde");
        assertThat(mde.get("cityName").asString()).isEqualTo("Medellín");
        assertThat(api.adminGet("bog", "/api/admin/me").get("city").asString()).isEqualTo("bog");
    }

    @Test
    void missingOrUnknownTokenIs401() {
        JsonNode missing = api.call(get("/api/admin/me"), 401);
        assertThat(missing.get("code").asString()).isEqualTo("UNAUTHORIZED");
        JsonNode wrong = api.call(get("/api/admin/state").header("X-Admin-Token", "not-a-real-admin-token-xxxx"), 401);
        assertThat(wrong.get("code").asString()).isEqualTo("UNAUTHORIZED");
        api.call(get("/api/admin/rounds").header("X-Admin-Token", ApiClient.MDE_ADMIN + "x"), 401);
    }

    @Test
    void anAdminCannotActOnAnotherCitysPlayersOrEntries() {
        String practice = api.createRound("bog", "Practice", "an apple", false).get("id").asString();
        JsonNode joined = api.join("bog", "BogPlayer");
        String token = joined.get("token").asString();
        String playerId = joined.get("playerId").asString();
        api.adminPost("bog", "/api/admin/rounds/" + practice + "/start");
        String attempt = api.attempt(token, "a pear");
        api.awaitIdle("bog");
        api.select(token, attempt);

        assertThat(api.adminPost("mde", "/api/admin/players/" + playerId + "/kick", null, 403).get("code").asString())
                .isEqualTo("ADMIN_FORBIDDEN");
        assertThat(api.adminPost("mde", "/api/admin/players/" + playerId + "/rename", Map.of("nickname", "Hacked"), 403)
                .get("code").asString()).isEqualTo("ADMIN_FORBIDDEN");
        assertThat(api.adminPost("mde", "/api/admin/entries/" + attempt + "/hide", Map.of("hidden", true), 403)
                .get("code").asString()).isEqualTo("ADMIN_FORBIDDEN");
        assertThat(api.adminPost("mde", "/api/admin/rounds/" + practice + "/close", null, 409).get("code").asString())
                .as("Medellín has no open run of this round").isEqualTo("INVALID_TRANSITION");
        assertThat(api.adminGet("mde", "/api/admin/players")).isEmpty();
        assertThat(api.adminGet("mde", "/api/admin/entries?roundId=" + practice)).isEmpty();

        api.adminPost("bog", "/api/admin/players/" + playerId + "/kick", null, 204);
        assertThat(api.call(ApiClient.player(get("/api/me"), token), 403).get("code").asString())
                .isEqualTo("PLAYER_KICKED");
    }

    @Test
    void tokensAreRequiredLongEnoughAndDistinct() {
        CityCatalog catalog = new CityCatalog("mde:Medellín,bog:Bogotá");
        MockEnvironment ok = new MockEnvironment().withProperty("ADMIN_TOKEN_MDE", "a".repeat(24))
                .withProperty("ADMIN_TOKEN_BOG", "b".repeat(30));
        AdminTokens tokens = new AdminTokens(ok, catalog);
        assertThat(tokens.cityFor("a".repeat(24))).contains("mde");
        assertThat(tokens.cityFor("b".repeat(30))).contains("bog");
        assertThat(tokens.cityFor("a".repeat(23))).isEmpty();
        assertThat(tokens.cityFor(null)).isEmpty();

        MockEnvironment shortToken = new MockEnvironment().withProperty("ADMIN_TOKEN_MDE", "a".repeat(23))
                .withProperty("ADMIN_TOKEN_BOG", "b".repeat(30));
        assertThatThrownBy(() -> new AdminTokens(shortToken, catalog)).hasMessageContaining("ADMIN_TOKEN_MDE")
                .hasMessageContaining("24").hasMessageNotContaining("aaaa");
        MockEnvironment missing = new MockEnvironment().withProperty("ADMIN_TOKEN_MDE", "a".repeat(24));
        assertThatThrownBy(() -> new AdminTokens(missing, catalog)).hasMessageContaining("ADMIN_TOKEN_BOG");
        MockEnvironment same = new MockEnvironment().withProperty("ADMIN_TOKEN_MDE", "a".repeat(24))
                .withProperty("ADMIN_TOKEN_BOG", "a".repeat(24));
        assertThatThrownBy(() -> new AdminTokens(same, catalog)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shortTokenFailsStartup() {
        assertThatThrownBy(() -> new org.springframework.boot.builder.SpringApplicationBuilder(
                io.github.ewcastroh.promptbattle.PromptBattleApiApplication.class)
                .run("--server.port=0", "--ADMIN_TOKEN_MDE=too-short").close())
                .hasStackTraceContaining("ADMIN_TOKEN_MDE must be at least 24 characters");
    }

    @Test
    void cityCodesAreValidated() {
        assertThatThrownBy(() -> new CityCatalog("MDE:Medellín")).hasMessageContaining("Invalid city code");
        assertThatThrownBy(() -> new CityCatalog("mde")).hasMessageContaining("no display name");
        assertThatThrownBy(() -> new CityCatalog("mde:A,mde:B")).hasMessageContaining("Duplicate");
        assertThat(new CityCatalog(" mde : Medellín , bog:Bogotá").cities()).containsOnlyKeys("mde", "bog");
    }
}
