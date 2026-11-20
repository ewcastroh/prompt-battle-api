package io.github.ewcastroh.promptbattle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.ewcastroh.promptbattle.generation.FakeImageGenerator;
import io.github.ewcastroh.promptbattle.generation.GenerationDispatcher;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/**
 * SPEC §3.3, §5.5, D-018: the shared FIFO, the per-provider limit, both cities' timers and the grace period
 * together. One slow provider with 2 slots; Medellín closes with work running and queued while Bogotá's round
 * stays open with work queued behind Medellín's.
 */
@SpringBootTest(properties = { "app.fake.max-concurrency=2", "app.game.round-grace-seconds=2" })
@AutoConfigureMockMvc
class GraceAndQueueTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    GenerationDispatcher dispatcher;
    ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc);
        api.resetEvent();
    }

    private JsonNode attemptOf(String token) {
        return api.me(token).get("attempts").get(0);
    }

    @Test
    void graceKeepsWhatFinishesInTimeDropsTheRestAndBogotaKeepsItsPlaceInTheSharedQueue() {
        String round = api.createRound("mde", "Lighthouse", "[target] a lighthouse", true).get("id").asString();
        List<String> mde = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            mde.add(api.join("mde", "mde_slow" + i).get("token").asString());
        }
        List<String> bog = List.of(api.join("bog", "bog_quick1").get("token").asString(),
                api.join("bog", "bog_quick2").get("token").asString());
        api.adminPost("mde", "/api/admin/rounds/" + round + "/start");
        api.adminPost("bog", "/api/admin/rounds/" + round + "/start");

        List<String> mdeAttempts = new ArrayList<>();
        for (int i = 0; i < mde.size(); i++) {
            mdeAttempts.add(api.attempt(mde.get(i), "[delay=1500] [sim=0.5] slow lighthouse " + i));
        }
        await().atMost(Duration.ofSeconds(5)).until(() -> dispatcher.inFlight(FakeImageGenerator.ID) == 2
                && dispatcher.length() == 4);
        for (int i = 0; i < bog.size(); i++) {
            api.attempt(bog.get(i), "[sim=0.6] quick lighthouse " + i);
        }
        assertThat(attemptOf(bog.get(0)).get("queuePosition").asInt()).as("behind Medellín's 4 queued jobs")
                .isEqualTo(5);
        assertThat(attemptOf(bog.get(1)).get("queuePosition").asInt()).isEqualTo(6);

        api.adminPost("mde", "/api/admin/rounds/" + round + "/close"); // grace: 2 s
        assertThat(attemptOf(bog.get(0)).get("status").asString()).as("still waiting its turn").isEqualTo("QUEUED");

        api.awaitPhase("mde", "ROUND_SCORED");
        await().atMost(Duration.ofSeconds(10)).until(() -> bog.stream()
                .allMatch(t -> "DONE".equals(attemptOf(t).get("status").asString())));

        // Medellín: the two jobs running at close finished within grace and count; the next two started late
        // and were dropped when grace ended; the last two never left the queue. None used an attempt.
        for (int i = 0; i < mde.size(); i++) {
            JsonNode a = attemptOf(mde.get(i));
            if (i < 2) {
                assertThat(a.get("status").asString()).as("attempt %d", i).isEqualTo("DONE");
            } else {
                assertThat(a.get("status").asString()).as("attempt %d", i).isEqualTo("FAILED");
                assertThat(a.get("code").asString()).isEqualTo("ROUND_NOT_OPEN");
                assertThat(api.me(mde.get(i)).get("round").get("attemptsLeft").asInt()).isEqualTo(3);
            }
        }
        JsonNode entries = api.adminGet("mde", "/api/admin/entries?roundId=" + round);
        assertThat(entries).hasSize(2);
        entries.forEach(e -> assertThat(e.get("auto").asBoolean()).isTrue());

        assertThat(api.state("bog").get("phase").asString()).as("Bogotá's round is untouched").isEqualTo("ROUND_OPEN");
        assertThat(dispatcher.peakInFlight(FakeImageGenerator.ID)).as("the provider limit held").isLessThanOrEqualTo(2);
    }
}
