package io.github.ewcastroh.promptbattle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.github.ewcastroh.promptbattle.game.FailureKind;
import io.github.ewcastroh.promptbattle.generation.FakeImageGenerator;
import io.github.ewcastroh.promptbattle.generation.GenerationRequest;
import io.github.ewcastroh.promptbattle.generation.GenerationResult;
import io.github.ewcastroh.promptbattle.generation.ImageGenerator;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/** SPEC §5.4 and §14.1-3 (PLAN A2 FailoverTest): event-wide image provider failover, end to end over HTTP. */
@SpringBootTest(properties = { "app.image.provider-chain=fal,bfl", "app.image.load-split=" })
@AutoConfigureMockMvc
class FailoverTest {

    static final InProcessProvider FAL = new InProcessProvider("fal");
    static final InProcessProvider BFL = new InProcessProvider("bfl");
    static final Duration WAIT = Duration.ofSeconds(5);
    static final Duration POLL = Duration.ofMillis(10);

    /** The chain's two providers as in-process doubles; the fake profile's own provider stays outside the chain. */
    @TestConfiguration(proxyBeanMethods = false)
    static class TwoProviders {

        @Bean
        ImageGenerator falProvider() {
            return FAL;
        }

        @Bean
        ImageGenerator bflProvider() {
            return BFL;
        }
    }

    /** The fake generator under another provider id: its markers still work, and {@code down} fails every call. */
    static final class InProcessProvider implements ImageGenerator {

        private final String id;
        private final FakeImageGenerator fake;
        final AtomicBoolean down = new AtomicBoolean();
        final AtomicInteger calls = new AtomicInteger();

        InProcessProvider(String id) {
            this.id = id;
            this.fake = new FakeImageGenerator(id + "-test-model", 5, 0, 4);
        }

        @Override
        public String providerId() {
            return id;
        }

        @Override
        public String modelId() {
            return fake.modelId();
        }

        @Override
        public int maxConcurrency() {
            return fake.maxConcurrency();
        }

        @Override
        public GenerationResult generate(GenerationRequest request) {
            calls.incrementAndGet();
            if (down.get()) {
                return new GenerationResult.Failure(FailureKind.PROVIDER_ERROR, "provider down");
            }
            return fake.generate(request);
        }
    }

    @Autowired
    MockMvc mvc;
    ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc);
        FAL.down.set(false);
        BFL.down.set(false);
        // The event reset keeps the active provider, so put fal first again (a forced provider's circuit closes).
        force("mde", "bfl");
        force("mde", "fal");
        api.resetEvent(); // fresh players, runs and alerts; every circuit closed and every counter cleared
    }

    @Test
    void threeFailuresSwitchTheOpenRunsOfBothCitiesToTheNextProvider() {
        String round = practiceRoundOpenIn("mde", "bog");
        assertThat(imageProvider("mde")).isEqualTo("fal");
        assertThat(imageProvider("bog")).isEqualTo("fal");

        failThreeTimes("Kite", "mde", "bog");

        awaitAlert("CIRCUIT_OPEN", Map.of("providerId", "fal"));
        awaitAlert("PROVIDER_SWITCHED", Map.of("from", "fal", "to", "bfl"));
        assertThat(alerts("CIRCUIT_OPEN")).hasSize(1);
        assertThat(alerts("PROVIDER_SWITCHED")).hasSize(1);
        assertThat(api.adminGet("bog", "/api/admin/alerts")).as("every admin sees the same alerts")
                .isEqualTo(api.adminGet("mde", "/api/admin/alerts"));
        assertThat(imageProvider("mde")).isEqualTo("bfl");
        assertThat(imageProvider("bog")).isEqualTo("bfl");

        for (String city : List.of("mde", "bog")) {
            JsonNode entry = entryGenerated(city, round, "Boat_" + city);
            assertThat(entry.get("provider").asString()).as("provider of the next %s attempt", city).isEqualTo("bfl");
            assertThat(entry.get("model").asString()).isEqualTo(BFL.modelId());
        }

        JsonNode providers = api.adminGet("bog", "/api/admin/providers");
        assertThat(providers.get("active").asString()).isEqualTo("bfl");
        JsonNode fal = imageRow(providers, "fal");
        assertThat(fal.get("circuit").asString()).isEqualTo("OPEN");
        assertThat(fal.get("failureCount").asLong()).isEqualTo(3);
        assertThat(fal.get("lastError").get("kind").asString()).isEqualTo("PROVIDER_ERROR");
        JsonNode bfl = imageRow(providers, "bfl");
        assertThat(bfl.get("circuit").asString()).isEqualTo("CLOSED");
        assertThat(bfl.get("successCount").asLong()).isEqualTo(2);
    }

    @Test
    void tenSafetyBlocksNeverOpenACircuitOrSwitchTheProvider() {
        String round = practiceRoundOpenIn("mde", "bog");
        int callsBefore = FAL.calls.get();
        List<String[]> attempts = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String token = player(i % 2 == 0 ? "mde" : "bog", "Unsafe" + i);
            attempts.add(new String[] { token, api.attempt(token, "[unsafe] a garden gnome " + i) });
        }
        for (String[] a : attempts) {
            JsonNode view = finished(a[0], a[1]);
            assertThat(view.get("status").asString()).isEqualTo("BLOCKED");
            assertThat(view.get("code").asString()).isEqualTo("PROMPT_BLOCKED");
        }
        assertThat(FAL.calls.get() - callsBefore).as("every block came from the provider").isEqualTo(10);

        assertThat(alerts("CIRCUIT_OPEN")).isEmpty();
        assertThat(alerts("PROVIDER_SWITCHED")).isEmpty();
        JsonNode providers = api.adminGet("mde", "/api/admin/providers");
        assertThat(providers.get("active").asString()).isEqualTo("fal");
        JsonNode fal = imageRow(providers, "fal");
        assertThat(fal.get("circuit").asString()).isEqualTo("CLOSED");
        assertThat(fal.get("failureCount").asLong()).isZero();
        assertThat(fal.get("lastError").isNull()).as("lastError").isTrue();
        assertThat(imageProvider("mde")).isEqualTo("fal");
        assertThat(imageProvider("bog")).isEqualTo("fal");
        assertThat(entryGenerated("mde", round, "Calm").get("provider").asString()).isEqualTo("fal");
    }

    @Test
    void eitherAdminCanForceTheActiveProvider() {
        String round = practiceRoundOpenIn("mde");

        JsonNode providers = force("bog", "bfl");

        assertThat(providers.get("active").asString()).isEqualTo("bfl");
        assertThat(strings(providers.get("chain"))).containsExactly("fal", "bfl");
        assertThat(imageRow(providers, "bfl").get("circuit").asString()).isEqualTo("CLOSED");
        awaitAlert("PROVIDER_FORCED", Map.of("providerId", "bfl", "byCity", "bog"));
        assertThat(alerts("PROVIDER_SWITCHED")).as("a forced switch is its own alert").isEmpty();
        assertThat(imageProvider("mde")).as("Medellín's open run follows Bogotá's forced switch").isEqualTo("bfl");
        assertThat(entryGenerated("mde", round, "Forced").get("provider").asString()).isEqualTo("bfl");
    }

    @Test
    void onlyAConfiguredChainProviderCanBeForced() {
        // runware is not built (D-011); fake has an adapter in this context but is not in IMAGE_PROVIDER_CHAIN.
        for (String id : List.of("runware", "fake")) {
            JsonNode problem = api.adminPost("mde", "/api/admin/providers/active", Map.of("providerId", id), 404);
            assertThat(problem.get("code").asString()).isEqualTo("PROVIDER_NOT_FOUND");
            assertThat(problem.get("params").get("providerId").asString()).isEqualTo(id);
        }
        assertThat(api.adminGet("mde", "/api/admin/providers").get("active").asString()).isEqualTo("fal");
        assertThat(alerts("PROVIDER_FORCED")).isEmpty();
    }

    @Test
    void forcingTheFailedProviderBackClosesItsCircuit() {
        String round = practiceRoundOpenIn("mde");
        failThreeTimes("Back", "mde");
        awaitAlert("PROVIDER_SWITCHED", Map.of("from", "fal", "to", "bfl"));
        assertThat(imageProvider("mde")).isEqualTo("bfl");

        JsonNode providers = force("mde", "fal");

        assertThat(providers.get("active").asString()).isEqualTo("fal");
        assertThat(imageRow(providers, "fal").get("circuit").asString()).isEqualTo("CLOSED");
        awaitAlert("PROVIDER_FORCED", Map.of("providerId", "fal", "byCity", "mde"));
        awaitAlert("CIRCUIT_CLOSED", Map.of("providerId", "fal"));
        assertThat(imageProvider("mde")).isEqualTo("fal");
        assertThat(entryGenerated("mde", round, "Returned").get("provider").asString()).isEqualTo("fal");
    }

    @Test
    void aRecoveredProviderDoesNotTakeBackTheActiveRole() {
        String round = practiceRoundOpenIn("mde");
        failThreeTimes("Probe", "mde");
        awaitAlert("PROVIDER_SWITCHED", Map.of("from", "fal", "to", "bfl"));

        // The probe also runs on an open circuit and its success closes it; there is no automatic fail-back (D-023).
        JsonNode report = api.adminPost("mde", "/api/admin/providers/probe");

        assertThat(probeRow(report, "fal").get("ok").asBoolean()).isTrue();
        awaitAlert("CIRCUIT_CLOSED", Map.of("providerId", "fal"));
        JsonNode providers = api.adminGet("mde", "/api/admin/providers");
        assertThat(imageRow(providers, "fal").get("circuit").asString()).isEqualTo("CLOSED");
        assertThat(providers.get("active").asString()).isEqualTo("bfl");
        assertThat(alerts("PROVIDER_SWITCHED")).hasSize(1);
        assertThat(imageProvider("mde")).isEqualTo("bfl");
        assertThat(entryGenerated("mde", round, "Steady").get("provider").asString()).isEqualTo("bfl");
    }

    @Test
    void withEveryCircuitOpenNoRoundStartsUntilAProviderRecovers() {
        String round = practiceRoundOpenIn("mde");
        failThreeTimes("Down", "mde");
        awaitAlert("PROVIDER_SWITCHED", Map.of("from", "fal", "to", "bfl"));
        failThreeTimes("Dead", "mde");
        awaitAlert("CIRCUIT_OPEN", Map.of("providerId", "bfl"));
        assertThat(alerts("PROVIDER_SWITCHED")).as("no healthy provider to switch to").hasSize(1);
        assertThat(imageProvider("mde")).isEqualTo("bfl");

        JsonNode start = api.adminPost("bog", "/api/admin/rounds/" + round + "/start", null, 503);
        assertThat(start.get("code").asString()).isEqualTo("PROVIDER_UNAVAILABLE");
        JsonNode preview = api.adminPost("bog", "/api/admin/rounds/generate-target",
                Map.of("prompt", "a quiet harbor"), 503);
        assertThat(preview.get("code").asString()).isEqualTo("PROVIDER_UNAVAILABLE");
        JsonNode providers = api.adminGet("bog", "/api/admin/providers");
        assertThat(imageRow(providers, "fal").get("circuit").asString()).isEqualTo("OPEN");
        assertThat(imageRow(providers, "bfl").get("circuit").asString()).isEqualTo("OPEN");
        assertThat(providers.get("active").asString()).isEqualTo("bfl");

        // fal is back, bfl still down: fal's circuit closes and, as the active provider is unhealthy, fal takes over.
        BFL.down.set(true);
        JsonNode report = api.adminPost("bog", "/api/admin/providers/probe");

        assertThat(probeRow(report, "fal").get("ok").asBoolean()).isTrue();
        assertThat(probeRow(report, "bfl").get("ok").asBoolean()).isFalse();
        assertThat(probeRow(report, "bfl").get("errorKind").asString()).isEqualTo("PROVIDER_ERROR");
        awaitAlert("CIRCUIT_CLOSED", Map.of("providerId", "fal"));
        awaitAlert("PROVIDER_SWITCHED", Map.of("from", "bfl", "to", "fal"));
        assertThat(imageProvider("mde")).isEqualTo("fal");
        JsonNode bog = api.adminPost("bog", "/api/admin/rounds/" + round + "/start");
        assertThat(bog.get("round").get("imageProvider").asString()).isEqualTo("fal");
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode force(String city, String providerId) {
        return api.adminPost(city, "/api/admin/providers/active", Map.of("providerId", providerId), 200);
    }

    /** A practice round (no calibration) started in the given cities. */
    private String practiceRoundOpenIn(String... cities) {
        String round = api.createRound("mde", "Failover", "a lighthouse at dusk", false).get("id").asString();
        for (String city : cities) {
            api.adminPost(city, "/api/admin/rounds/" + round + "/start");
        }
        return round;
    }

    private String imageProvider(String city) {
        return api.state(city).get("round").get("imageProvider").asString();
    }

    private String player(String city, String nickname) {
        return api.join(city, nickname).get("token").asString();
    }

    /** Waits until the attempt is no longer QUEUED or GENERATING and returns the player's view of it. */
    private JsonNode finished(String token, String attemptId) {
        JsonNode[] view = new JsonNode[1];
        await("attempt " + attemptId + " finished").atMost(WAIT).pollInterval(POLL).until(() -> {
            view[0] = api.call(ApiClient.player(get("/api/attempts/" + attemptId), token), 200);
            String status = view[0].get("status").asString();
            return !"QUEUED".equals(status) && !"GENERATING".equals(status);
        });
        return view[0];
    }

    /** Three provider failures within 30 s from players of the given cities, in turn (the circuit is event-wide). */
    private void failThreeTimes(String nickname, String... cities) {
        List<String[]> attempts = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String token = player(cities[i % cities.length], nickname + i);
            attempts.add(new String[] { token, api.attempt(token, "[fail] a red kite " + i) });
        }
        for (String[] a : attempts) {
            JsonNode view = finished(a[0], a[1]);
            assertThat(view.get("status").asString()).isEqualTo("FAILED");
            assertThat(view.get("code").asString()).isEqualTo("GENERATION_FAILED");
        }
    }

    /** A new player's successful attempt, chosen as their entry; returns the admin's view of that entry. */
    private JsonNode entryGenerated(String city, String round, String nickname) {
        String token = player(city, nickname);
        String attemptId = api.attempt(token, "a paper boat on a lake");
        assertThat(finished(token, attemptId).get("status").asString()).isEqualTo("DONE");
        api.select(token, attemptId);
        for (JsonNode e : api.adminGet(city, "/api/admin/entries?roundId=" + round)) {
            if (e.get("entryId").asString().equals(attemptId)) {
                return e;
            }
        }
        throw new AssertionError("entry " + attemptId + " is not listed");
    }

    private List<JsonNode> alerts(String code) {
        List<JsonNode> found = new ArrayList<>();
        for (JsonNode a : api.adminGet("mde", "/api/admin/alerts")) {
            if (code.equals(a.get("code").asString())) {
                found.add(a);
            }
        }
        return found;
    }

    /** Waits for an event-wide alert ({@code city} null) with exactly these params. */
    private void awaitAlert(String code, Map<String, String> params) {
        await("alert " + code + " " + params).atMost(WAIT).pollInterval(POLL)
                .until(() -> alerts(code).stream().anyMatch(a -> {
                    JsonNode actual = a.get("params");
                    return a.get("city").isNull() && actual.size() == params.size() && params.entrySet().stream()
                            .allMatch(p -> actual.has(p.getKey())
                                    && p.getValue().equals(actual.get(p.getKey()).asString()));
                }));
    }

    private static JsonNode imageRow(JsonNode providers, String id) {
        for (JsonNode p : providers.get("image")) {
            if (id.equals(p.get("id").asString())) {
                return p;
            }
        }
        throw new AssertionError("no image provider " + id);
    }

    private static JsonNode probeRow(JsonNode report, String providerId) {
        for (JsonNode r : report.get("results")) {
            if ("IMAGE".equals(r.get("kind").asString()) && providerId.equals(r.get("providerId").asString())) {
                return r;
            }
        }
        throw new AssertionError("no IMAGE probe result for " + providerId);
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(v -> values.add(v.asString()));
        return values;
    }
}
