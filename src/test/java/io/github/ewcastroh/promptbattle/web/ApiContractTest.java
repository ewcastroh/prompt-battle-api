package io.github.ewcastroh.promptbattle.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import io.github.ewcastroh.promptbattle.ApiClient;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/** JSON field names and shapes match docs/API.md; fields that don't apply are present with null. */
@SpringBootTest
@AutoConfigureMockMvc
class ApiContractTest {

    static final String INSTANT = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z";
    static final String UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    @Autowired
    MockMvc mvc;
    ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc);
        api.resetEvent(); // test classes share one cached context
    }

    private static void assertFields(JsonNode node, String... names) {
        assertThat(node.propertyNames()).as("fields of %s", node).containsExactlyInAnyOrder(names);
    }

    @Test
    void citiesAndJoin() {
        JsonNode cities = api.call(get("/api/cities"), 200);
        assertThat(cities).hasSize(2);
        assertFields(cities.get(0), "code", "name");
        assertThat(cities.get(0).get("code").asString()).isEqualTo("mde");
        assertThat(cities.get(1).get("name").asString()).isEqualTo("Bogotá");

        JsonNode joined = api.join("mde", "Contract_1");
        assertFields(joined, "playerId", "token", "claimCode", "city");
        assertThat(joined.get("playerId").asString()).matches(UUID);
        assertThat(joined.get("token").asString()).startsWith("pt_").hasSize(43);
        assertThat(joined.get("claimCode").asString()).matches("[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{4}");
        assertThat(joined.get("city").asString()).isEqualTo("mde");

        String token = joined.get("token").asString();
        JsonNode again = api.call(ApiClient.withIp(ApiClient.player(
                api.jsonPost("/api/cities/mde/players", Map.of("nickname", "Ignored")), token)), 200);
        assertThat(again.get("playerId").asString()).isEqualTo(joined.get("playerId").asString());

        JsonNode taken = api.call(ApiClient.withIp(api.jsonPost("/api/cities/mde/players",
                Map.of("nickname", "contract_1"))), 409);
        assertThat(taken.get("code").asString()).isEqualTo("NICKNAME_TAKEN");
        JsonNode invalid = api.call(ApiClient.withIp(api.jsonPost("/api/cities/mde/players", Map.of("nickname", "x"))),
                400);
        assertThat(invalid.get("params").get("min").asInt()).isEqualTo(3);
        assertThat(invalid.get("params").get("max").asInt()).isEqualTo(16);
        JsonNode city = api.call(ApiClient.withIp(api.jsonPost("/api/cities/cali/players", Map.of("nickname", "Ana"))),
                404);
        assertThat(city.get("code").asString()).isEqualTo("CITY_NOT_FOUND");
        assertThat(city.get("params").get("city").asString()).isEqualTo("cali");
    }

    @Test
    void problemDetailShape() {
        MockHttpServletResponse r = api.perform(get("/api/me"));
        assertThat(r.getStatus()).isEqualTo(401);
        assertThat(r.getContentType()).startsWith("application/problem+json");
        JsonNode body = api.call(get("/api/me"), 401);
        assertThat(body.get("code").asString()).isEqualTo("UNAUTHORIZED");
        assertThat(body.get("status").asInt()).isEqualTo(401);
        assertThat(body.get("title").asString()).isEqualTo("Unauthorized");
        assertThat(body.get("instance").asString()).isEqualTo("/api/me");
        assertThat(body.has("detail")).isTrue();

        assertThat(api.call(get("/api/nothing-here"), 404).get("code").asString()).isEqualTo("NOT_FOUND");
        assertThat(api.call(get("/img/00000000-0000-0000-0000-000000000000.jpg"), 404).get("code").asString())
                .isEqualTo("NOT_FOUND");
        assertThat(api.call(get("/img/../snapshot.json"), 404).get("code").asString()).isEqualTo("NOT_FOUND");
        assertThat(api.call(get("/api/cities/cali/qr.png"), 404).get("code").asString()).isEqualTo("CITY_NOT_FOUND");
        assertThat(api.call(api.admin(get("/api/admin/entries"), "mde"), 400).get("params").get("field").asString())
                .isEqualTo("roundId");
        assertThat(api.adminPost("mde", "/api/admin/event/reset", Map.of("confirm", "yes"), 400).get("code")
                .asString()).isEqualTo("RESET_NOT_CONFIRMED");
        assertThat(api.call(api.admin(api.jsonPost("/api/admin/rounds", null).content("{bad"), "mde"), 400)
                .get("code").asString()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void playerStageAdminAndRoundShapes() {
        JsonNode round = api.createRound("bog", "Contract round", "[target] a lighthouse", true);
        assertFields(round, "id", "position", "name", "scored", "hiddenPrompt", "durationSec", "attemptsPerPlayer",
                "seed", "targetUrl", "targetThumbUrl", "targetSource", "locked", "calibration", "runs");
        String roundId = round.get("id").asString();
        JsonNode ready = api.round("bog", roundId);
        assertThat(ready.get("seed").asLong()).isEqualTo(4242);
        assertThat(ready.get("targetUrl").asString()).matches("/img/" + UUID + "\\.jpg");
        assertThat(ready.get("targetThumbUrl").asString()).matches("/img/" + UUID + "_thumb\\.jpg");
        assertThat(ready.get("targetSource").asString()).isEqualTo("GENERATED");
        assertFields(ready.get("calibration"), "status", "provider", "anchors");
        assertThat(ready.get("calibration").get("anchors")).hasSize(6);
        assertFields(ready.get("calibration").get("anchors").get(0), "prompt", "imageUrl", "thumbUrl", "excluded");
        assertFields(ready.get("runs").get(0), "city", "status", "startedAt", "endsAt", "extensions", "imageProvider",
                "embeddingProvider", "floor", "mixedProviders");
        assertThat(ready.get("runs").get(0).get("startedAt").isNull()).isTrue();

        // Stage in the lobby
        JsonNode stage = api.stage("bog");
        assertFields(stage, "city", "phase", "joinUrl", "qrUrl", "playersCount", "nicknames", "round",
                "practiceGallery", "reveal", "hiddenPrompts");
        assertThat(stage.get("joinUrl").asString()).isEqualTo("https://ui.test/play/bog");
        assertThat(stage.get("qrUrl").asString()).isEqualTo("/api/cities/bog/qr.png");
        assertThat(stage.get("round").isNull()).isTrue();
        assertThat(stage.get("reveal").isNull()).isTrue();

        // Player in an open round
        String token = api.join("bog", "Shape_1").get("token").asString();
        JsonNode lobbyMe = api.me(token);
        assertThat(lobbyMe.get("round").isNull()).isTrue();
        assertThat(lobbyMe.get("attempts")).isEmpty();
        api.adminPost("bog", "/api/admin/rounds/" + roundId + "/start");
        JsonNode submitted = api.call(ApiClient.player(api.jsonPost("/api/rounds/current/attempts",
                Map.of("prompt", "[delay=300] [sim=0.5] a lighthouse")), token), 202);
        assertFields(submitted, "attemptId", "queuePosition");
        JsonNode me = api.me(token);
        assertFields(me, "player", "phase", "round", "attempts", "final");
        assertFields(me.get("player"), "id", "nickname", "claimCode", "city", "cityName");
        assertFields(me.get("round"), "id", "name", "scored", "targetUrl", "targetThumbUrl", "startedAt", "endsAt",
                "attemptsPerPlayer", "attemptsLeft", "entryAttemptId");
        assertThat(me.get("round").get("startedAt").asString()).matches(INSTANT);
        assertThat(me.get("final").isNull()).isTrue();
        JsonNode attempt = me.get("attempts").get(0);
        assertFields(attempt, "id", "status", "prompt", "queuePosition", "imageUrl", "thumbUrl", "code", "createdAt");
        assertThat(attempt.get("status").asString()).isIn("QUEUED", "GENERATING");
        assertThat(attempt.get("imageUrl").isNull()).isTrue();
        api.awaitIdle("bog");
        JsonNode done = api.call(ApiClient.player(get("/api/attempts/" + submitted.get("attemptId").asString()), token),
                200);
        assertThat(done.get("status").asString()).isEqualTo("DONE");
        assertThat(done.get("code").isNull()).isTrue();
        assertThat(done.get("queuePosition").isNull()).isTrue();
        MockHttpServletResponse image = api.perform(get(done.get("imageUrl").asString()));
        assertThat(image.getStatus()).isEqualTo(200);
        assertThat(image.getContentType()).isEqualTo("image/jpeg");
        assertThat(image.getHeader("Cache-Control")).isEqualTo("max-age=31536000, public, immutable");
        assertThat(api.perform(get(done.get("thumbUrl").asString())).getStatus()).isEqualTo(200);

        JsonNode openStage = api.stage("bog");
        assertFields(openStage.get("round"), "id", "name", "scored", "targetUrl", "startedAt", "endsAt",
                "submissionsCount", "attemptsCount", "status");
        assertThat(openStage.get("nicknames").isNull()).isTrue();

        JsonNode admin = api.state("bog");
        assertFields(admin, "city", "phase", "playersCount", "scoredRoundsDone", "round", "miniPodium", "podium",
                "practiceGalleryShown");
        assertFields(admin.get("round"), "id", "name", "scored", "status", "startedAt", "endsAt", "extensions",
                "imageProvider", "embeddingProvider", "submissionsCount", "inFlight");
        assertThat(admin.get("round").get("imageProvider").asString()).isEqualTo("fake");

        api.adminPost("bog", "/api/admin/rounds/" + roundId + "/close");
        JsonNode scored = api.awaitPhase("bog", "ROUND_SCORED");
        assertFields(scored.get("miniPodium"), "roundId", "size", "step", "preview");
        assertThat(scored.get("miniPodium").get("step").isNull()).as("live preview").isTrue();
        assertFields(scored.get("miniPodium").get("preview").get(0), "rank", "playerId", "nickname", "entryId", "score",
                "revealed", "commentaryReady");
        assertThat(scored.get("podium").get("roundId").isNull()).isTrue();

        JsonNode entries = api.adminGet("bog", "/api/admin/entries?roundId=" + roundId);
        assertFields(entries.get(0), "entryId", "playerId", "nickname", "prompt", "promptEn", "imageUrl", "thumbUrl",
                "provider", "model", "embeddingProvider", "cos", "floor", "score", "hidden", "auto", "selectedAt");
        assertThat(entries.get(0).get("score").asDouble()).isEqualTo(50.0);
        assertThat(entries.get(0).get("auto").asBoolean()).isTrue();
        assertThat(entries.get(0).get("model").asString()).isEqualTo("fake-v1");

        JsonNode players = api.adminGet("bog", "/api/admin/players");
        assertFields(players.get(0), "id", "nickname", "claimCode", "joinedAt", "kicked", "flagged", "blockedCount",
                "attemptsCount", "total");
        assertThat(players.get(0).get("total").asDouble()).isEqualTo(50.0);

        JsonNode alerts = api.adminGet("bog", "/api/admin/alerts");
        assertThat(alerts.isArray()).isTrue();
    }

    @Test
    void roundCrudWithUploadsAndLock() throws Exception {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1600, 900, BufferedImage.TYPE_INT_ARGB), "png", png);
        String roundJson = api.toJson(Map.of("name", "Uploaded", "hiddenPrompt", "a photo", "scored", false));
        MockMultipartFile roundPart = new MockMultipartFile("round", "", "application/json", roundJson.getBytes());
        MockMultipartFile target = new MockMultipartFile("target", "t.png", "image/png", png.toByteArray());
        JsonNode created = api.call(api.admin(multipart("/api/admin/rounds").file(roundPart).file(target), "mde"), 201);
        assertThat(created.get("targetSource").asString()).isEqualTo("UPLOADED");
        assertThat(created.get("durationSec").asInt()).isEqualTo(180);
        assertThat(created.get("calibration").get("status").asString()).isEqualTo("NOT_REQUIRED");
        String id = created.get("id").asString();
        BufferedImage stored = ImageIO.read(new java.io.ByteArrayInputStream(
                api.perform(get(created.get("targetUrl").asString())).getContentAsByteArray()));
        assertThat(List.of(stored.getWidth(), stored.getHeight())).containsExactly(1024, 1024);

        MockMultipartFile text = new MockMultipartFile("target", "t.txt", "text/plain", "hello".getBytes());
        assertThat(api.call(api.admin(multipart("/api/admin/rounds").file(roundPart).file(text), "mde"), 400)
                .get("code").asString()).isEqualTo("UPLOAD_INVALID");
        MockMultipartFile webp = new MockMultipartFile("target", "t.webp", "image/webp", png.toByteArray());
        assertThat(api.call(api.admin(multipart("/api/admin/rounds").file(roundPart).file(webp), "mde"), 400)
                .get("code").asString()).as("D-022: JPEG and PNG only").isEqualTo("UPLOAD_INVALID");
        MockMultipartFile fakeJpeg = new MockMultipartFile("target", "t.jpg", "image/jpeg", "not an image".getBytes());
        assertThat(api.call(api.admin(multipart("/api/admin/rounds").file(roundPart).file(fakeJpeg), "mde"), 400)
                .get("code").asString()).isEqualTo("UPLOAD_INVALID");
        assertThat(api.call(api.admin(multipart("/api/admin/rounds").file(roundPart), "mde"), 400)
                .get("code").asString()).isEqualTo("UPLOAD_INVALID");

        JsonNode updated = api.call(api.admin(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/admin/rounds/" + id).contentType(MediaType.APPLICATION_JSON)
                .content(api.toJson(Map.of("name", "Renamed"))), "bog"), 200);
        assertThat(updated.get("name").asString()).as("either admin edits shared rounds").isEqualTo("Renamed");

        api.adminPost("mde", "/api/admin/rounds/" + id + "/start");
        assertThat(api.call(api.admin(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/admin/rounds/" + id).contentType(MediaType.APPLICATION_JSON)
                .content(api.toJson(Map.of("name", "Again"))), "bog"), 409).get("code").asString())
                .isEqualTo("ROUND_LOCKED");
        assertThat(api.call(api.admin(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/admin/rounds/" + id), "bog"), 409).get("code").asString()).isEqualTo("ROUND_LOCKED");
        assertThat(api.call(api.admin(multipart("/api/admin/rounds/" + id).file(roundPart).with(r -> {
            r.setMethod("PUT");
            return r;
        }), "bog"), 409).get("code").asString()).isEqualTo("ROUND_LOCKED");
        api.adminPost("mde", "/api/admin/rounds/" + id + "/close");
    }

    @Test
    void eventResetClearsEveryCityAndInvalidatesTokens() {
        String token = api.join("mde", "Reset_1").get("token").asString();
        api.createRound("bog", "To be deleted", "an apple", false);
        api.resetEvent();
        assertThat(api.call(ApiClient.player(get("/api/me"), token), 401).get("code").asString())
                .isEqualTo("UNAUTHORIZED");
        assertThat(api.adminGet("bog", "/api/admin/rounds")).isEmpty();
        assertThat(api.stage("mde").get("playersCount").asInt()).isZero();
    }

    @Test
    void qrCodeIsAPngOfTheJoinUrl() throws Exception {
        MockHttpServletResponse r = api.perform(get("/api/cities/mde/qr.png"));
        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(r.getContentType()).isEqualTo("image/png");
        BufferedImage qr = ImageIO.read(new java.io.ByteArrayInputStream(r.getContentAsByteArray()));
        assertThat(qr.getWidth()).isEqualTo(1024);
    }

    @Test
    void generateTargetErrors() {
        assertThat(api.adminPost("mde", "/api/admin/rounds/generate-target", Map.of("prompt", " "), 400)
                .get("code").asString()).isEqualTo("PROMPT_INVALID");
        assertThat(api.adminPost("mde", "/api/admin/rounds/generate-target", Map.of("prompt", "[unsafe] x"), 422)
                .get("code").asString()).isEqualTo("PROMPT_BLOCKED");
        JsonNode failed = api.adminPost("mde", "/api/admin/rounds/generate-target", Map.of("prompt", "[fail] x"), 502);
        assertThat(failed.get("code").asString()).isEqualTo("GENERATION_FAILED");
        assertThat(failed.get("params").get("kind").asString()).isEqualTo("PROVIDER_ERROR");
        JsonNode preview = api.adminPost("mde", "/api/admin/rounds/generate-target", Map.of("prompt", "a cat"), 200);
        assertFields(preview, "previewId", "imageUrl", "thumbUrl", "seed", "provider", "model");
        assertThat(preview.get("provider").asString()).isEqualTo("fake");
    }
}
