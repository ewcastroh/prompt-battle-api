package io.github.ewcastroh.promptbattle.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.ewcastroh.promptbattle.ApiClient;
import io.github.ewcastroh.promptbattle.generation.FakeImageGenerator;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/**
 * PLAN I2: the fake profile can mirror the event's {@code fal,bfl} split with {@code fake,fake2}, each with its
 * own slot limit, so the load test measures the queue the event will have.
 */
@SpringBootTest(properties = {
        "app.image.provider-chain=fake,fake2",
        "app.image.load-split=fake,fake2",
        "app.fake.latency-ms=300",
        "app.fake.max-concurrency=1",
        "app.fake.second-max-concurrency=2"})
@AutoConfigureMockMvc
class FakeLoadSplitTest {

    @Autowired
    MockMvc mvc;

    @Test
    void overflowRunsOnTheSecondFakeWithItsOwnLimit() {
        ApiClient api = new ApiClient(mvc);
        api.resetEvent();
        JsonNode providers = api.adminGet("mde", "/api/admin/providers");
        assertThat(ids(providers.get("chain"))).containsExactly(FakeImageGenerator.ID, FakeImageGenerator.SECOND_ID);
        assertThat(ids(providers.get("loadSplit"))).containsExactly(FakeImageGenerator.ID,
                FakeImageGenerator.SECOND_ID);
        assertThat(maxConcurrency(providers)).containsEntry(FakeImageGenerator.ID, 1)
                .containsEntry(FakeImageGenerator.SECOND_ID, 2);

        String round = api.createRound("mde", "Practice", "a red bicycle [target]", false).get("id").asString();
        api.adminPost("mde", "/api/admin/rounds/" + round + "/start");
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tokens.add(api.join("mde", "Split_" + i).get("token").asString());
        }
        tokens.forEach(t -> api.attempt(t, "a blue bicycle"));
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(50)).until(() -> tokens.stream()
                .allMatch(t -> "DONE".equals(api.me(t).get("attempts").get(0).get("status").asString())));

        Map<String, Long> successes = successes(api.adminGet("mde", "/api/admin/providers"));
        assertThat(successes.get(FakeImageGenerator.SECOND_ID)).isPositive();
        assertThat(successes.get(FakeImageGenerator.ID) + successes.get(FakeImageGenerator.SECOND_ID))
                .isEqualTo(7); // six attempts plus the target preview on the active provider
    }

    private static List<String> ids(JsonNode array) {
        List<String> ids = new ArrayList<>();
        array.forEach(n -> ids.add(n.asString()));
        return ids;
    }

    private static Map<String, Integer> maxConcurrency(JsonNode providers) {
        Map<String, Integer> limits = new HashMap<>();
        providers.get("image").forEach(p -> limits.put(p.get("id").asString(), p.get("maxConcurrency").asInt()));
        return limits;
    }

    private static Map<String, Long> successes(JsonNode providers) {
        Map<String, Long> counts = new HashMap<>();
        providers.get("image").forEach(p -> counts.put(p.get("id").asString(), p.get("successCount").asLong()));
        return counts;
    }
}
