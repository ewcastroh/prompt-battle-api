package io.github.ewcastroh.promptbattle.commentary;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.ewcastroh.promptbattle.game.Commentary;
import io.github.ewcastroh.promptbattle.game.Effect;
import io.github.ewcastroh.promptbattle.generation.FakeImageGenerator;
import io.github.ewcastroh.promptbattle.moderation.Blocklist;
import io.github.ewcastroh.promptbattle.persistence.ImageStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * PLAN A3: {@link SpringAiCommentary} against a stubbed Anthropic Messages API. Checks the request (auth
 * header, model, effort, native structured output schema, both images in order, no sampling parameters) and
 * that every failure throws, so {@code GameRuntime} falls back to a canned comment.
 */
class CommentaryWireMockTest {

    static final WireMockServer WM = new WireMockServer(wireMockConfig().dynamicPort());

    @TempDir
    Path dataDir;
    ImageStore images;
    String targetId;
    String entryId;

    @BeforeAll
    static void start() {
        WM.start();
    }

    @AfterAll
    static void stop() {
        WM.stop();
    }

    @BeforeEach
    void setUp() {
        WM.resetAll();
        images = new ImageStore(dataDir);
        targetId = images.store(FakeImageGenerator.render("a chiva bus in the rain", 1, 512, 512));
        entryId = images.store(FakeImageGenerator.render("colorful bus under rain", 2, 512, 512));
    }

    SpringAiCommentary commentary(Duration timeout) {
        return new SpringAiCommentary(AnthropicStubs.client(WM, timeout), images, new Blocklist());
    }

    Effect.CommentaryJob job(Double total) {
        return new Effect.CommentaryJob("final:p1", targetId, entryId, 71.4, 5, total);
    }

    @Test
    void sendsBothImagesScoreRankAndSchemaAndReturnsBothLanguages() throws Exception {
        AnthropicStubs.stub(WM, AnthropicStubs.reply(
                "{\"es\":\"¡Esa chiva casi se sale de la pantalla!\",\"en\":\"That chiva nearly drove off the screen!\"}"));

        Commentary c = commentary(Duration.ofSeconds(5)).comment(job(128.9));

        assertThat(c.es()).isEqualTo("¡Esa chiva casi se sale de la pantalla!");
        assertThat(c.en()).isEqualTo("That chiva nearly drove off the screen!");

        WM.verify(1, postRequestedFor(urlEqualTo("/v1/messages"))
                // anthropic-java 3.x sends an API key as a bearer token (not X-Api-Key).
                .withHeader("Authorization", equalTo("Bearer " + AnthropicStubs.KEY))
                .withHeader("anthropic-version", com.github.tomakehurst.wiremock.client.WireMock.matching(".+"))
                .withoutHeader("X-Api-Key"));
        JsonNode body = AnthropicStubs.request(WM, 0);
        assertThat(body.get("model").asString()).isEqualTo(AnthropicStubs.MODEL);
        assertThat(body.get("max_tokens").asInt()).isEqualTo(AnthropicChat.MAX_TOKENS);
        assertThat(body.has("temperature") || body.has("top_p") || body.has("top_k"))
                .as("Claude Sonnet 5.5 rejects sampling parameters").isFalse();
        assertThat(body.path("thinking").path("type").asString("")).as("thinking is never disabled")
                .isNotEqualTo("disabled");
        assertThat(body.get("output_config").get("effort").asString()).isEqualTo("low");

        JsonNode format = body.get("output_config").get("format");
        assertThat(format.get("type").asString()).isEqualTo("json_schema");
        JsonNode schema = format.get("schema");
        assertThat(schema.get("type").asString()).isEqualTo("object");
        assertThat(schema.get("additionalProperties").asBoolean(true)).isFalse();
        assertThat(names(schema.get("properties"))).containsExactlyInAnyOrder("es", "en");
        assertThat(schema.get("properties").get("es").get("type").asString()).isEqualTo("string");
        assertThat(schema.get("properties").get("en").get("type").asString()).isEqualTo("string");
        assertThat(texts(schema.get("required"))).containsExactlyInAnyOrder("es", "en");

        assertThat(systemText(body)).contains("Colombian Spanish").contains("at most 20 words")
                .contains("Never mention people's bodies");

        JsonNode messages = body.get("messages");
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).get("role").asString()).isEqualTo("user");
        List<JsonNode> imagesSent = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (JsonNode block : messages.get(0).get("content")) {
            switch (block.get("type").asString()) {
                case "image" -> imagesSent.add(block.get("source"));
                case "text" -> text.append(block.get("text").asString());
                default -> throw new AssertionError("unexpected block " + block);
            }
        }
        assertThat(text.toString()).contains("Podium rank 5").contains("71.4").contains("128.9")
                .contains("Image 1 is the target");
        assertThat(imagesSent).hasSize(2);
        for (JsonNode source : imagesSent) {
            assertThat(source.get("type").asString()).isEqualTo("base64");
            assertThat(source.get("media_type").asString()).isEqualTo("image/jpeg");
        }
        assertThat(imagesSent.get(0).get("data").asString()).as("target first, as the thumbnail")
                .isEqualTo(base64(images.file(targetId, true).orElseThrow()));
        assertThat(imagesSent.get(1).get("data").asString())
                .isEqualTo(base64(images.file(entryId, true).orElseThrow()));
    }

    @Test
    void miniPodiumJobHasNoTotal() {
        AnthropicStubs.stub(WM, AnthropicStubs.reply("{\"es\":\"¡Muy cerca!\",\"en\":\"So close!\"}"));
        commentary(Duration.ofSeconds(5)).comment(job(null));
        JsonNode content = AnthropicStubs.request(WM, 0).get("messages").get(0).get("content");
        StringBuilder text = new StringBuilder();
        content.forEach(b -> text.append(b.path("text").asString("")));
        assertThat(text.toString()).doesNotContain("Event total");
    }

    @Test
    void refusalThrows() {
        AnthropicStubs.stub(WM, AnthropicStubs.refusal());
        assertThatThrownBy(() -> commentary(Duration.ofSeconds(5)).comment(job(null)))
                .isInstanceOf(AnthropicChat.ModelRefusedException.class);
        AnthropicStubs.stub(WM, AnthropicStubs.partialRefusal("{\"es\":\"Bien\",\"en\":\"Good\"}"));
        assertThatThrownBy(() -> commentary(Duration.ofSeconds(5)).comment(job(null)))
                .as("a refusal is never used, even when its partial text parses")
                .isInstanceOf(AnthropicChat.ModelRefusedException.class);
    }

    @Test
    void serverErrorThrowsWithoutRetrying() {
        AnthropicStubs.stub(WM, aResponse().withStatus(529).withHeader("Content-Type", "application/json")
                .withBody("{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}"));
        assertThatThrownBy(() -> commentary(Duration.ofSeconds(5)).comment(job(null)))
                .isInstanceOf(RuntimeException.class);
        WM.verify(1, postRequestedFor(urlEqualTo("/v1/messages")));
    }

    @Test
    void slowReplyTimesOutWithinTheBudget() {
        AnthropicStubs.stub(WM, AnthropicStubs.reply("{\"es\":\"tarde\",\"en\":\"late\"}").withFixedDelay(5_000));
        long started = System.nanoTime();
        assertThatThrownBy(() -> commentary(Duration.ofMillis(500)).comment(job(null)))
                .isInstanceOf(RuntimeException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        WM.verify(1, postRequestedFor(urlEqualTo("/v1/messages")));
    }

    @Test
    void blankLongOrBlocklistedTextIsRejected() {
        SpringAiCommentary c = commentary(Duration.ofSeconds(5));
        AnthropicStubs.stub(WM, AnthropicStubs.reply("{\"es\":\"   \",\"en\":\"Nice!\"}"));
        assertThatThrownBy(() -> c.comment(job(null))).isInstanceOf(SpringAiCommentary.InvalidCommentaryException.class);

        String longText = "word ".repeat(SpringAiCommentary.MAX_WORDS + 1).strip();
        AnthropicStubs.stub(WM, AnthropicStubs.reply("{\"es\":\"¡Bien!\",\"en\":\"" + longText + "\"}"));
        assertThatThrownBy(() -> c.comment(job(null))).isInstanceOf(SpringAiCommentary.InvalidCommentaryException.class);

        AnthropicStubs.stub(WM, AnthropicStubs.reply("{\"es\":\"¡Qué m13rda tan bonita!\",\"en\":\"Lovely!\"}"));
        assertThatThrownBy(() -> c.comment(job(null))).isInstanceOf(SpringAiCommentary.InvalidCommentaryException.class);
    }

    @Test
    void malformedJsonThrows() {
        AnthropicStubs.stub(WM, AnthropicStubs.reply("not json"));
        assertThatThrownBy(() -> commentary(Duration.ofSeconds(5)).comment(job(null)))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void reportsItselfLive() {
        assertThat(commentary(Duration.ofSeconds(1)).live()).isTrue();
    }

    static String systemText(JsonNode body) {
        JsonNode system = body.get("system");
        if (system.isString()) {
            return system.asString();
        }
        StringBuilder text = new StringBuilder();
        system.forEach(b -> text.append(b.path("text").asString("")));
        return text.toString();
    }

    static List<String> names(JsonNode object) {
        List<String> names = new ArrayList<>();
        object.propertyNames().forEach(names::add);
        return names;
    }

    static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(n -> values.add(n.asString()));
        return values;
    }

    static String base64(Path file) throws Exception {
        return Base64.getEncoder().encodeToString(Files.readAllBytes(file));
    }
}
