package io.github.ewcastroh.promptbattle.commentary;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ewcastroh.promptbattle.ApiClient;
import io.github.ewcastroh.promptbattle.RoundPlay;
import io.github.ewcastroh.promptbattle.game.Commentary;
import io.github.ewcastroh.promptbattle.game.Effect;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/**
 * SPEC §4.4 / PLAN A3: the reveal never waits on an AI call. The commentary model sleeps 30 s on every call;
 * comments are precomputed (scoring, {@code podium/start}) and time out into canned ones, and every
 * {@code mini-podium/next} and {@code podium/next} returns in under 200 ms, including the step whose comment
 * was requested only a moment before (an entry hidden mid-podium moves a new player into the podium).
 */
@SpringBootTest(properties = "app.commentary.timeout=1s")
@AutoConfigureMockMvc
class RevealNeverWaitsTest {

    static final Duration NEXT_BUDGET = Duration.ofMillis(200);
    static final AtomicInteger CALLS = new AtomicInteger();

    @TestConfiguration
    static class SleepingModel {
        @Bean
        @Primary
        CommentaryPort sleepingCommentary() {
            return new CommentaryPort() {
                @Override
                public boolean live() {
                    return true;
                }

                @Override
                public Commentary comment(Effect.CommentaryJob job) {
                    CALLS.incrementAndGet();
                    try {
                        Thread.sleep(30_000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return new Commentary("ai-es", "ai-en");
                }
            };
        }
    }

    @Autowired
    MockMvc mvc;
    ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc);
        api.resetEvent();
        CALLS.set(0);
    }

    @Test
    void miniPodiumAndPodiumNextNeverWaitOnTheModel() {
        String round = RoundPlay.scoredRound(api, "mde", "Café al amanecer");
        List<RoundPlay.Player> players = RoundPlay.join(api, "mde", "rv", 6);
        List<String> entries = RoundPlay.play(api, "mde", round, players, 90.0, 80.0, 70.0, 60.0, 50.0, 40.0);
        assertThat(CALLS.get()).as("mini-podium comments were requested at scoring").isEqualTo(3);

        api.adminPost("mde", "/api/admin/mini-podium/start");
        for (int i = 0; i < 3; i++) {
            timedNext("/api/admin/mini-podium/next");
        }

        api.adminPost("mde", "/api/admin/podium/start");
        api.awaitPhase("mde", "PODIUM_REVEAL");
        assertThat(CALLS.get()).as("final comments were requested at podium/start").isEqualTo(3 + 5);
        timedNext("/api/admin/podium/next"); // #5: rv_05 (50.0)

        // Hide #4's entry (rv_04, 60.0): rv_06 (40.0) moves into the podium and its comment is requested now.
        api.adminPost("mde", "/api/admin/entries/" + entries.get(3) + "/hide", Map.of("hidden", true), 204);
        assertThat(CALLS.get()).isEqualTo(3 + 5 + 1);
        timedNext("/api/admin/podium/next"); // #4: rv_06, while its AI call is still sleeping

        for (int i = 0; i < 4; i++) {
            timedNext("/api/admin/podium/next"); // #3, #2, #1, then FINISHED
        }
        assertThat(api.state("mde").get("phase").asString()).isEqualTo("FINISHED");

        JsonNode revealed = api.stage("mde").get("reveal").get("entries");
        assertThat(revealed).hasSize(5);
        JsonNode fourth = revealed.get(1);
        assertThat(fourth.get("nickname").asString()).isEqualTo("rv_06");
        CannedLines.assertCanned(fourth.get("commentary").get("es").asString(),
                fourth.get("commentary").get("en").asString(), 40.0);
        for (JsonNode e : revealed) {
            assertThat(e.get("commentary").get("es").asString()).isNotEqualTo("ai-es");
        }
    }

    private void timedNext(String path) {
        long started = System.nanoTime();
        api.adminPost("mde", path);
        Duration took = Duration.ofNanos(System.nanoTime() - started);
        assertThat(took).as("%s took %s ms", path, took.toMillis()).isLessThan(NEXT_BUDGET);
    }
}
