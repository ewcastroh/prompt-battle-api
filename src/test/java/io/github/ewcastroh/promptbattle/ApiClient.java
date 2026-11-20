package io.github.ewcastroh.promptbattle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Drives the REST API through MockMvc (filters included). */
public final class ApiClient {

    public static final String MDE_ADMIN = "test-admin-token-medellin-0000";
    public static final String BOG_ADMIN = "test-admin-token-bogota-00000";
    private static final AtomicInteger IP = new AtomicInteger();

    private final MockMvc mvc;
    private final JsonMapper json = JsonMapper.builder().build();

    public ApiClient(MockMvc mvc) {
        this.mvc = mvc;
    }

    public static String adminToken(String city) {
        return switch (city) {
            case "mde" -> MDE_ADMIN;
            case "bog" -> BOG_ADMIN;
            default -> throw new IllegalArgumentException(city);
        };
    }

    public MockHttpServletResponse perform(RequestBuilder request) {
        try {
            return mvc.perform(request).andReturn().getResponse();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Performs the request, checks the status and returns the JSON body (null when empty). */
    public JsonNode call(RequestBuilder request, int status) {
        MockHttpServletResponse response = perform(request);
        String body = body(response);
        assertThat(response.getStatus()).as("%s → %s", request, body).isEqualTo(status);
        return body.isEmpty() ? null : json.readTree(body);
    }

    public static String body(MockHttpServletResponse response) {
        try {
            return response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    public String toJson(Object value) {
        return json.writeValueAsString(value);
    }

    public MockHttpServletRequestBuilder jsonPost(String path, Object body) {
        return post(path).contentType(MediaType.APPLICATION_JSON).content(body == null ? "" : toJson(body));
    }

    public <B extends AbstractMockHttpServletRequestBuilder<B>> B admin(B request, String city) {
        return request.header("X-Admin-Token", adminToken(city));
    }

    public static MockHttpServletRequestBuilder player(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    // ---- players

    public JsonNode join(String city, String nickname) {
        return call(withIp(jsonPost("/api/cities/" + city + "/players", Map.of("nickname", nickname))), 201);
    }

    public static MockHttpServletRequestBuilder withIp(MockHttpServletRequestBuilder request) {
        int n = IP.incrementAndGet();
        return request.with(r -> {
            r.setRemoteAddr("10." + ((n >> 16) & 255) + "." + ((n >> 8) & 255) + "." + (n & 255));
            return r;
        });
    }

    public JsonNode me(String token) {
        return call(player(get("/api/me"), token), 200);
    }

    public String attempt(String token, String prompt) {
        return call(player(jsonPost("/api/rounds/current/attempts", Map.of("prompt", prompt)), token), 202)
                .get("attemptId").asString();
    }

    public void select(String token, String attemptId) {
        call(player(jsonPost("/api/rounds/current/entry", Map.of("attemptId", attemptId)), token), 204);
    }

    // ---- admin

    public JsonNode adminGet(String city, String path) {
        return call(admin(get(path), city), 200);
    }

    public JsonNode adminPost(String city, String path, Object body, int status) {
        return call(admin(jsonPost(path, body), city), status);
    }

    public JsonNode adminPost(String city, String path) {
        return adminPost(city, path, null, 200);
    }

    /** The global event reset (API.md §7): every city back to an empty lobby. */
    public void resetEvent() {
        adminPost("mde", "/api/admin/event/reset", Map.of("confirm", "RESET"), 204);
    }

    public JsonNode state(String city) {
        return adminGet(city, "/api/admin/state");
    }

    public JsonNode stage(String city) {
        return call(get("/api/cities/" + city + "/stage/state"), 200);
    }

    public String stageText(String city) {
        return body(perform(get("/api/cities/" + city + "/stage/state")));
    }

    /** Creates a round from a generated target and waits for calibration (scored rounds). */
    public JsonNode createRound(String city, String name, String targetPrompt, boolean scored) {
        JsonNode preview = adminPost(city, "/api/admin/rounds/generate-target",
                Map.of("prompt", targetPrompt, "seed", 4242), 200);
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("name", name);
        body.put("hiddenPrompt", targetPrompt);
        body.put("scored", scored);
        body.put("previewId", preview.get("previewId").asString());
        JsonNode round = adminPost(city, "/api/admin/rounds", body, 201);
        String id = round.get("id").asString();
        if (scored) {
            awaitRound(city, id, r -> "READY".equals(r.get("calibration").get("status").asString()));
        }
        return round;
    }

    public JsonNode round(String city, String id) {
        for (JsonNode r : adminGet(city, "/api/admin/rounds")) {
            if (r.get("id").asString().equals(id)) {
                return r;
            }
        }
        throw new AssertionError("round " + id + " not found");
    }

    public void awaitRound(String city, String id, Predicate<JsonNode> condition) {
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(20))
                .until(() -> condition.test(round(city, id)));
    }

    public JsonNode awaitState(String city, Predicate<JsonNode> condition) {
        JsonNode[] last = new JsonNode[1];
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(20)).until(() -> {
            last[0] = state(city);
            return condition.test(last[0]);
        });
        return last[0];
    }

    public JsonNode awaitPhase(String city, String phase) {
        return awaitState(city, s -> phase.equals(s.get("phase").asString()));
    }

    public void awaitIdle(String city) {
        awaitState(city, s -> s.get("round").get("inFlight").asInt() == 0);
    }
}
