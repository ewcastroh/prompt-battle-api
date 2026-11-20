package io.github.ewcastroh.promptbattle.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.github.ewcastroh.promptbattle.ApiClient;
import io.github.ewcastroh.promptbattle.config.AppProperties;
import io.github.ewcastroh.promptbattle.generation.FakeImageGenerator;
import io.github.ewcastroh.promptbattle.generation.ProviderRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import tools.jackson.databind.JsonNode;

/** API.md §7 provider endpoints and the SPEC §7.6 probe on the fake profile (PLAN A2 ProviderController). */
@SpringBootTest
@AutoConfigureMockMvc
class ProviderControllerTest {

    /** API.md §1: ISO-8601 UTC with milliseconds. */
    static final Pattern INSTANT = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");

    @Autowired
    MockMvc mvc;
    @Autowired
    AppProperties props;
    @Autowired
    ProviderRegistry registry;
    ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc);
        api.resetEvent(); // test classes share one cached context
    }

    @Test
    void providersAndTheForcedSwitchReturnEveryContractField() {
        JsonNode providers = api.adminGet("mde", "/api/admin/providers");

        assertContractShape(providers);
        assertThat(providers.get("active").asString()).isEqualTo(FakeImageGenerator.ID);
        assertThat(strings(providers.get("chain"))).containsExactly(FakeImageGenerator.ID);
        assertThat(strings(providers.get("loadSplit"))).isEmpty();
        assertThat(providers.get("queueLength").asInt()).isZero();
        JsonNode image = providers.get("image").get(0);
        assertThat(image.get("id").asString()).isEqualTo(FakeImageGenerator.ID);
        assertThat(image.get("model").asString()).isEqualTo(props.fake().model());
        assertThat(image.get("configured").asBoolean()).isTrue();
        assertThat(image.get("circuit").asString()).isEqualTo("CLOSED");
        assertThat(image.get("inFlight").asInt()).isZero();
        assertThat(image.get("maxConcurrency").asInt()).isEqualTo(props.fake().maxConcurrency());
        JsonNode embedding = providers.get("embedding").get(0);
        assertThat(embedding.get("id").asString()).isEqualTo("fake");
        assertThat(embedding.get("model").asString()).isEqualTo(props.fake().model());
        assertThat(embedding.get("configured").asBoolean()).isTrue();
        assertThat(embedding.get("healthy").asBoolean()).isTrue();
        assertThat(providers.get("commentary").get("model").asString()).isEqualTo(props.commentary().model());
        assertThat(providers.get("commentary").get("configured").asBoolean()).as("canned comments only").isFalse();

        JsonNode forced = api.adminPost("bog", "/api/admin/providers/active",
                Map.of("providerId", FakeImageGenerator.ID), 200);

        assertContractShape(forced);
        assertThat(forced.get("active").asString()).isEqualTo(FakeImageGenerator.ID);
        List<JsonNode> alerts = alerts("PROVIDER_FORCED");
        assertThat(alerts).hasSize(1);
        JsonNode alert = alerts.getFirst();
        assertThat(alert.get("city").isNull()).as("event-wide alert").isTrue();
        assertThat(alert.get("params").get("providerId").asString()).isEqualTo(FakeImageGenerator.ID);
        assertThat(alert.get("params").get("byCity").asString()).isEqualTo("bog");
    }

    @Test
    void imageStatsFollowTheProvidersCalls() {
        JsonNode before = fakeImage();
        String round = api.createRound("bog", "Stats", "a teapot on a shelf", false).get("id").asString();
        api.adminPost("bog", "/api/admin/rounds/" + round + "/start");
        String token = api.join("bog", "StatsPlayer").get("token").asString();
        awaitStatus(token, api.attempt(token, "[fail] a teapot"), "FAILED");
        awaitStatus(token, api.attempt(token, "a teapot in the rain"), "DONE");

        JsonNode after = fakeImage();

        assertThat(after.get("successCount").asLong() - before.get("successCount").asLong())
                .as("target preview and attempt").isEqualTo(2);
        assertThat(after.get("failureCount").asLong() - before.get("failureCount").asLong()).isEqualTo(1);
        assertThat(after.get("lastError").propertyNames()).containsExactlyInAnyOrder("kind", "at");
        assertThat(after.get("lastError").get("kind").asString()).isEqualTo("PROVIDER_ERROR");
        assertThat(after.get("lastError").get("at").asString()).matches(INSTANT);
        assertThat(after.get("p50Ms").isNumber()).as("p50Ms").isTrue();
        assertThat(after.get("p95Ms").asLong()).isGreaterThanOrEqualTo(after.get("p50Ms").asLong());
        assertThat(after.get("circuit").asString()).as("one failure does not open the circuit").isEqualTo("CLOSED");
        assertThat(after.get("inFlight").asInt()).isZero();
    }

    @Test
    void forcingWithoutAProviderIdIsAValidationError() {
        for (Object body : new Object[] { null, Map.of(), Map.of("providerId", " ") }) {
            JsonNode problem = api.adminPost("mde", "/api/admin/providers/active", body, 400);
            assertThat(problem.get("code").asString()).isEqualTo("VALIDATION_FAILED");
            assertThat(problem.get("params").get("field").asString()).isEqualTo("providerId");
        }
        assertThat(alerts("PROVIDER_FORCED")).isEmpty();
    }

    @Test
    void providerEndpointsNeedAnAdminToken() {
        long successes = registry.stats(FakeImageGenerator.ID).successCount();
        List<RequestBuilder> requests = List.of(get("/api/admin/providers"),
                post("/api/admin/providers/active").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"providerId\":\"fake\"}"),
                post("/api/admin/providers/probe"),
                get("/api/admin/providers").header(AdminAuthInterceptor.HEADER, "not-an-admin-token-of-any-city"));

        for (RequestBuilder request : requests) {
            assertThat(api.call(request, 401).get("code").asString()).isEqualTo("UNAUTHORIZED");
        }
        assertThat(registry.stats(FakeImageGenerator.ID).successCount()).as("no probe ran").isEqualTo(successes);
        assertThat(alerts("PROVIDER_FORCED")).as("no forced switch").isEmpty();
    }

    @Test
    void probeReportsTheFakeProvidersAndTheMissingCommentaryModel() {
        JsonNode report = api.adminPost("mde", "/api/admin/providers/probe");

        assertThat(report.propertyNames()).containsExactlyInAnyOrder("startedAt", "results");
        assertThat(report.get("startedAt").asString()).matches(INSTANT);
        List<String> kinds = new ArrayList<>();
        for (JsonNode r : report.get("results")) {
            assertThat(r.propertyNames()).containsExactlyInAnyOrder("kind", "providerId", "model", "ok", "latencyMs",
                    "errorKind", "determinismMaxDiff");
            kinds.add(r.get("kind").asString());
        }
        assertThat(kinds).containsExactly("IMAGE", "EMBEDDING", "COMMENTARY");

        JsonNode image = report.get("results").get(0);
        assertThat(image.get("providerId").asString()).isEqualTo(FakeImageGenerator.ID);
        assertThat(image.get("model").asString()).isEqualTo(props.fake().model());
        assertThat(image.get("ok").asBoolean()).isTrue();
        assertThat(image.get("latencyMs").asLong()).isNotNegative();
        assertThat(image.get("errorKind").isNull()).as("errorKind").isTrue();
        assertThat(image.get("determinismMaxDiff").isNull()).as("determinismMaxDiff").isTrue();

        JsonNode embedding = report.get("results").get(1);
        assertThat(embedding.get("providerId").asString()).isEqualTo("fake");
        assertThat(embedding.get("ok").asBoolean()).isTrue();
        assertThat(embedding.get("errorKind").isNull()).as("errorKind").isTrue();
        assertThat(embedding.get("determinismMaxDiff").isNumber()).isTrue();
        assertThat(embedding.get("determinismMaxDiff").asDouble()).isZero();

        JsonNode commentary = report.get("results").get(2);
        assertThat(commentary.get("providerId").asString()).isEqualTo("anthropic");
        assertThat(commentary.get("model").asString()).isEqualTo(props.commentary().model());
        assertThat(commentary.get("ok").asBoolean()).isFalse();
        assertThat(commentary.get("errorKind").asString()).isEqualTo("NOT_CONFIGURED");
        assertThat(commentary.get("latencyMs").isNull()).as("latencyMs").isTrue();

        JsonNode determinism = api.adminGet("bog", "/api/admin/providers").get("embedding").get(0).get("determinism");
        assertThat(determinism.get("maxDiff").asDouble()).isZero();
        assertThat(determinism.get("averaging").asBoolean()).isFalse();
        assertThat(determinism.get("checkedAt").asString()).matches(INSTANT);
        assertThat(alerts("DETERMINISM_AVERAGING")).as("a deterministic provider needs no averaging").isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    /** Every field of API.md {@code GET /api/admin/providers}; fields that don't apply are null, never omitted. */
    private static void assertContractShape(JsonNode providers) {
        assertThat(providers.propertyNames()).containsExactlyInAnyOrder("active", "chain", "loadSplit", "queueLength",
                "image", "embedding", "commentary");
        assertThat(providers.get("image")).isNotEmpty();
        for (JsonNode image : providers.get("image")) {
            assertThat(image.propertyNames()).containsExactlyInAnyOrder("id", "model", "configured", "circuit",
                    "inFlight", "maxConcurrency", "p50Ms", "p95Ms", "successCount", "failureCount", "lastError");
        }
        assertThat(providers.get("embedding")).isNotEmpty();
        for (JsonNode embedding : providers.get("embedding")) {
            assertThat(embedding.propertyNames()).containsExactlyInAnyOrder("id", "model", "configured", "healthy",
                    "determinism");
            assertThat(embedding.get("determinism").propertyNames()).containsExactlyInAnyOrder("maxDiff", "averaging",
                    "checkedAt");
        }
        assertThat(providers.get("commentary").propertyNames()).containsExactlyInAnyOrder("model", "configured");
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

    private JsonNode fakeImage() {
        for (JsonNode image : api.adminGet("bog", "/api/admin/providers").get("image")) {
            if (FakeImageGenerator.ID.equals(image.get("id").asString())) {
                return image;
            }
        }
        throw new AssertionError("no fake image provider");
    }

    private void awaitStatus(String token, String attemptId, String status) {
        await("attempt " + attemptId + " " + status).atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(10))
                .until(() -> status.equals(api.call(ApiClient.player(get("/api/attempts/" + attemptId), token), 200)
                        .get("status").asString()));
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(v -> values.add(v.asString()));
        return values;
    }
}
