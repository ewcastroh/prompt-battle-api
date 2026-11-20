package io.github.ewcastroh.promptbattle.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.ewcastroh.promptbattle.ApiClient;
import io.github.ewcastroh.promptbattle.PromptBattleApiApplication;
import io.github.ewcastroh.promptbattle.game.GameEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

/** SPEC §7.4, §14.1-5: kill and restart → the state of both cities is restored. */
class SnapshotRestoreTest {

    @TempDir
    Path dataDir;

    private ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(PromptBattleApiApplication.class)
                .run("--server.port=0", "--app.data-dir=" + dataDir);
    }

    private static ApiClient client(ConfigurableApplicationContext ctx) {
        return new ApiClient(MockMvcBuilders.webAppContextSetup((WebApplicationContext) ctx).build());
    }

    private static byte[] encode(ConfigurableApplicationContext ctx) {
        SnapshotCodec codec = ctx.getBean(SnapshotCodec.class);
        return ctx.getBean(GameEngine.class).execute(g -> codec.encode(g.state()));
    }

    @Test
    void bothCitiesSurviveARestart() throws Exception {
        Path snapshot = dataDir.resolve(SnapshotStore.FILE);
        String mdeToken;
        String round1;
        byte[] before;
        byte[] onDisk;
        try (ConfigurableApplicationContext first = start()) {
            ApiClient api = client(first);
            String practice = api.createRound("mde", "Practice", "an apple", false).get("id").asString();
            round1 = api.createRound("bog", "Round 1", "[target] coffee", true).get("id").asString();

            // Medellín: practice played, round 1 open with entries.
            mdeToken = api.join("mde", "Ana").get("token").asString();
            String mdeOther = api.join("mde", "Beto").get("token").asString();
            api.adminPost("mde", "/api/admin/rounds/" + practice + "/start");
            api.attempt(mdeToken, "apple");
            api.awaitIdle("mde");
            api.adminPost("mde", "/api/admin/rounds/" + practice + "/close");
            api.awaitPhase("mde", "LOBBY");
            api.adminPost("mde", "/api/admin/rounds/" + round1 + "/start");
            String chosen = api.attempt(mdeToken, "[sim=0.8] coffee");
            api.awaitIdle("mde");
            api.select(mdeToken, chosen);
            api.attempt(mdeOther, "[sim=0.3] tea");
            api.awaitIdle("mde");

            // Bogotá: round 1 scored and its mini-podium half revealed.
            String bogToken = api.join("bog", "Carla").get("token").asString();
            api.adminPost("bog", "/api/admin/rounds/" + round1 + "/start");
            api.attempt(bogToken, "[sim=0.6] coffee");
            api.awaitIdle("bog");
            api.adminPost("bog", "/api/admin/rounds/" + round1 + "/close");
            api.awaitPhase("bog", "ROUND_SCORED");
            api.adminPost("bog", "/api/admin/mini-podium/start");
            api.adminPost("bog", "/api/admin/mini-podium/next");

            before = encode(first);
            byte[] expected = before;
            await().atMost(Duration.ofSeconds(10)).until(() -> Files.exists(snapshot)
                    && Arrays.equals(Files.readAllBytes(snapshot), expected));
            onDisk = Files.readAllBytes(snapshot); // what a kill would leave behind
        }
        Files.write(snapshot, onDisk);

        try (ConfigurableApplicationContext second = start()) {
            assertThat(new String(encode(second))).isEqualTo(new String(before));
            ApiClient api = client(second);
            assertThat(api.state("mde").get("phase").asString()).isEqualTo("ROUND_OPEN");
            JsonNode bog = api.state("bog");
            assertThat(bog.get("phase").asString()).isEqualTo("MINI_PODIUM");
            assertThat(bog.get("miniPodium").get("step").asInt()).isEqualTo(1);
            assertThat(api.stage("bog").get("reveal").get("entries")).hasSize(1);

            // The game goes on: tokens still work, images are still there, scoring runs.
            JsonNode me = api.me(mdeToken);
            assertThat(me.get("round").get("attemptsLeft").asInt()).isEqualTo(2);
            String imageUrl = me.get("attempts").get(0).get("imageUrl").asString();
            assertThat(api.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(imageUrl))
                    .getStatus()).isEqualTo(200);
            api.adminPost("mde", "/api/admin/rounds/" + round1 + "/close");
            api.awaitPhase("mde", "ROUND_SCORED");
            JsonNode entries = api.adminGet("mde", "/api/admin/entries?roundId=" + round1);
            assertThat(entries).extracting(e -> e.get("score").asDouble()).containsExactly(80.0, 30.0);
        }
    }
}
