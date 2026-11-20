package io.github.ewcastroh.promptbattle.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.github.ewcastroh.promptbattle.ApiClient;
import io.github.ewcastroh.promptbattle.RoundPlay;
import io.github.ewcastroh.promptbattle.config.AppProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/**
 * SPEC §6.1 / §9.3, API.md §7: the global event reset needs the typed confirmation, deletes the snapshot and
 * every image in {@code DATA_DIR}, clears both cities, and old player tokens get 401. Admin tokens (config)
 * keep working.
 */
@SpringBootTest
@AutoConfigureMockMvc
class EventResetTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    AppProperties props;
    ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc);
        api.resetEvent();
    }

    @Test
    void resetNeedsTheExactConfirmation() {
        String token = api.join("mde", "Keep_Me").get("token").asString();
        for (Object body : new Object[] { Map.of("confirm", "reset"), Map.of("confirm", "yes"), Map.of() }) {
            JsonNode error = api.adminPost("bog", "/api/admin/event/reset", body, 400);
            assertThat(error.get("code").asString()).isEqualTo("RESET_NOT_CONFIRMED");
        }
        assertThat(api.call(api.admin(get("/api/admin/event/reset"), "mde"), 405)).isNotNull();
        assertThat(api.me(token).get("player").get("nickname").asString()).isEqualTo("Keep_Me");
    }

    @Test
    void resetDeletesEveryCitysDataImagesAndSnapshot() throws IOException {
        String mdeToken = api.join("mde", "Reset_Mde").get("token").asString();
        String bogToken = api.join("bog", "Reset_Bog").get("token").asString();
        String round = RoundPlay.scoredRound(api, "mde", "Antes del reset");
        RoundPlay.play(api, "mde", round, List.of(new RoundPlay.Player("Reset_Mde", mdeToken)), 80.0);

        Path dataDir = props.dataDir();
        Path snapshot = dataDir.resolve("snapshot.json");
        assertThat(files(dataDir.resolve("images"))).isNotEmpty();
        await().atMost(Duration.ofSeconds(5)).until(() -> Files.exists(snapshot)
                && Files.readString(snapshot, StandardCharsets.UTF_8).contains("Reset_Mde"));

        api.adminPost("bog", "/api/admin/event/reset", Map.of("confirm", "RESET"), 204);

        assertThat(files(dataDir.resolve("images"))).as("every image is deleted").isEmpty();
        // The periodic snapshot may already have written the new, empty state.
        assertThat(!Files.exists(snapshot) || !Files.readString(snapshot, StandardCharsets.UTF_8).contains("Reset_"))
                .as("the old snapshot is gone").isTrue();
        for (String token : List.of(mdeToken, bogToken)) {
            JsonNode error = api.call(ApiClient.player(get("/api/me"), token), 401);
            assertThat(error.get("code").asString()).isEqualTo("UNAUTHORIZED");
        }
        for (String city : List.of("mde", "bog")) {
            JsonNode state = api.state(city);
            assertThat(state.get("phase").asString()).isEqualTo("LOBBY");
            assertThat(state.get("playersCount").asInt()).isZero();
            assertThat(api.adminGet(city, "/api/admin/players")).isEmpty();
        }
        assertThat(api.adminGet("mde", "/api/admin/rounds")).isEmpty();
        assertThat(api.adminGet("mde", "/api/admin/alerts")).isEmpty();
        // The event is playable again; nicknames are free again.
        assertThat(api.join("mde", "Reset_Mde").get("token").asString()).isNotEqualTo(mdeToken);
    }

    private static List<Path> files(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.toList();
        }
    }
}
