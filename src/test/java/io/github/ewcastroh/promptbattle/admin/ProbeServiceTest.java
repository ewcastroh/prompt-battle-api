package io.github.ewcastroh.promptbattle.admin;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.ewcastroh.promptbattle.ApiClient;
import io.github.ewcastroh.promptbattle.commentary.AnthropicStubs;
import io.github.ewcastroh.promptbattle.commentary.CommentaryPort;
import io.github.ewcastroh.promptbattle.commentary.SpringAiCommentary;
import io.github.ewcastroh.promptbattle.config.AppProperties;
import io.github.ewcastroh.promptbattle.generation.BflImageGenerator;
import io.github.ewcastroh.promptbattle.generation.FakeImageGenerator;
import io.github.ewcastroh.promptbattle.generation.FalImageGenerator;
import io.github.ewcastroh.promptbattle.generation.ProviderRegistry;
import io.github.ewcastroh.promptbattle.scoring.DeterminismCheck;
import io.github.ewcastroh.promptbattle.scoring.EmbeddingService;
import io.github.ewcastroh.promptbattle.scoring.GeminiEmbeddingProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/**
 * SPEC §7.6 and §4.2 (PLAN A2/A3 ProbeServiceTest): the probe through the real fal, BFL, Gemini and Anthropic
 * (commentary) adapters.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("probe") // not "fake": the real adapters are wired, pointed at WireMock
class ProbeServiceTest {

    /** Started before the context reads its URLs; nothing here ever reaches a real provider. */
    static final WireMockServer WM = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        WM.start();
    }

    static final String FAL_KEY = "test-fal-key";
    static final String BFL_KEY = "test-bfl-key";
    static final String GEMINI_KEY = "test-gemini-key";
    /** fal's status and result URLs carry only the app id; the adapter uses the URLs returned at submit. */
    static final String FAL_REQUEST = "/fal-ai/flux-2/requests/764cabcf-b745-4b3e-ae38-1200304cf45b";
    static final String FAL_IMAGE = "/files/rabbit/probe.jpeg";
    static final byte[] JPEG = FakeImageGenerator.render("probe result", 7, 256, 256);
    static final double[] FIRST = embedding(0);
    static final double[] SECOND = embedding(0.002);
    static final Pattern INSTANT = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");

    @DynamicPropertySource
    static void providers(DynamicPropertyRegistry registry) {
        registry.add("app.fal.queue-url", WM::baseUrl);
        registry.add("app.bfl.base-url", WM::baseUrl);
        registry.add("app.gemini.base-url", WM::baseUrl);
        registry.add("app.fal.key", () -> FAL_KEY);
        registry.add("app.bfl.key", () -> BFL_KEY);
        registry.add("app.gemini.api-key", () -> GEMINI_KEY);
        registry.add("app.commentary.api-key", () -> AnthropicStubs.KEY);
        registry.add("app.commentary.base-url", WM::baseUrl);
        registry.add("app.image.provider-chain", () -> "fal,bfl");
        registry.add("app.image.load-split", () -> "fal,bfl");
        registry.add("app.embedding.provider-chain", () -> "gemini");
        registry.add("app.image.timeout-seconds", () -> "5");
        registry.add("app.embedding.timeout-seconds", () -> "5");
        registry.add("app.fal.poll-interval", () -> "10ms");
        registry.add("app.bfl.poll-interval", () -> "10ms");
    }

    @AfterAll
    static void stopWireMock() {
        WM.stop();
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    AppProperties props;
    @Autowired
    ProviderRegistry registry;
    @Autowired
    EmbeddingService embeddings;
    @Autowired
    CommentaryPort commentaryPort;
    ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc);
        api.resetEvent();
        WM.resetAll();
    }

    @Test
    void probeCallsEveryRealAdapterAndTurnsOnAveragingForANonDeterministicEmbedding() {
        assertThat(registry.generator("fal")).isInstanceOf(FalImageGenerator.class);
        assertThat(registry.generator("bfl")).isInstanceOf(BflImageGenerator.class);
        assertThat(registry.has(FakeImageGenerator.ID)).as("fake profile overridden").isFalse();
        assertThat(embeddings.provider("gemini")).isInstanceOf(GeminiEmbeddingProvider.class);
        stubFalSuccess();
        stubBflWithoutCredits();
        stubGeminiTwoSlightlyDifferentVectors();
        AnthropicStubs.stub(WM, AnthropicStubs.reply("{\"es\":\"¡Todo listo!\",\"en\":\"All set!\"}"));
        assertThat(commentaryPort).as("the live model is preferred over the canned comments")
                .isInstanceOf(SpringAiCommentary.class);
        double expectedDiff = DeterminismCheck.maxDiff(FIRST, SECOND);
        assertThat(expectedDiff).isGreaterThan(DeterminismCheck.THRESHOLD);

        JsonNode report = api.adminPost("mde", "/api/admin/providers/probe");

        assertThat(report.get("startedAt").asString()).matches(INSTANT);
        assertThat(report.get("results")).hasSize(4);
        JsonNode fal = report.get("results").get(0);
        assertThat(fal.get("kind").asString()).isEqualTo("IMAGE");
        assertThat(fal.get("providerId").asString()).isEqualTo("fal");
        assertThat(fal.get("model").asString()).isEqualTo(props.fal().model());
        assertThat(fal.get("ok").asBoolean()).isTrue();
        assertThat(fal.get("latencyMs").asLong()).isNotNegative();
        assertThat(fal.get("errorKind").isNull()).as("fal errorKind").isTrue();
        JsonNode bfl = report.get("results").get(1);
        assertThat(bfl.get("kind").asString()).isEqualTo("IMAGE");
        assertThat(bfl.get("providerId").asString()).isEqualTo("bfl");
        assertThat(bfl.get("model").asString()).isEqualTo(props.bfl().model());
        assertThat(bfl.get("ok").asBoolean()).isFalse();
        assertThat(bfl.get("errorKind").asString()).isEqualTo("PROVIDER_ERROR");
        JsonNode gemini = report.get("results").get(2);
        assertThat(gemini.get("kind").asString()).isEqualTo("EMBEDDING");
        assertThat(gemini.get("providerId").asString()).isEqualTo("gemini");
        assertThat(gemini.get("model").asString()).isEqualTo(props.gemini().embeddingModel());
        assertThat(gemini.get("ok").asBoolean()).isTrue();
        assertThat(gemini.get("determinismMaxDiff").asDouble()).isCloseTo(expectedDiff, within(1e-9));
        JsonNode commentary = report.get("results").get(3);
        assertThat(commentary.get("kind").asString()).isEqualTo("COMMENTARY");
        assertThat(commentary.get("providerId").asString()).isEqualTo("anthropic");
        assertThat(commentary.get("model").asString()).isEqualTo(props.commentary().model());
        assertThat(commentary.get("ok").asBoolean()).isTrue();
        assertThat(commentary.get("errorKind").isNull()).as("commentary errorKind").isTrue();
        assertThat(commentary.get("latencyMs").asLong()).isNotNegative();

        // What each adapter sent: keys only to the provider APIs, one image per embedding call, no retry on 402.
        WM.verify(1, postRequestedFor(urlEqualTo("/" + props.fal().model()))
                .withHeader("Authorization", equalTo("Key " + FAL_KEY)));
        WM.verify(1, getRequestedFor(urlEqualTo(FAL_IMAGE)).withoutHeader("Authorization"));
        WM.verify(1, postRequestedFor(urlEqualTo(bflSubmit())).withHeader("x-key", equalTo(BFL_KEY)));
        WM.verify(2, postRequestedFor(urlEqualTo(geminiEmbed())).withHeader("x-goog-api-key", equalTo(GEMINI_KEY))
                .withRequestBody(matchingJsonPath("$.content.parts[0].inlineData.mimeType", equalTo("image/jpeg")))
                .withRequestBody(matchingJsonPath("$.embedContentConfig.outputDimensionality", equalTo("768"))));
        WM.verify(1, postRequestedFor(urlEqualTo("/v1/messages"))
                .withHeader("Authorization", equalTo("Bearer " + AnthropicStubs.KEY))
                .withRequestBody(matchingJsonPath("$.model", equalTo(props.commentary().model()))));

        JsonNode providers = api.adminGet("bog", "/api/admin/providers");
        assertThat(providers.get("commentary").get("configured").asBoolean()).isTrue();
        JsonNode embedding = providers.get("embedding").get(0);
        assertThat(embedding.get("id").asString()).isEqualTo("gemini");
        assertThat(embedding.get("configured").asBoolean()).isTrue();
        assertThat(embedding.get("healthy").asBoolean()).isTrue();
        JsonNode determinism = embedding.get("determinism");
        assertThat(determinism.get("averaging").asBoolean()).isTrue();
        assertThat(determinism.get("maxDiff").asDouble()).isCloseTo(expectedDiff, within(1e-9));
        assertThat(determinism.get("checkedAt").asString()).matches(INSTANT);
        assertThat(embeddings.averaging("gemini")).as("scoring now averages two calls per image").isTrue();
        assertThat(imageRow(providers, "fal").get("successCount").asLong()).isEqualTo(1);
        assertThat(imageRow(providers, "bfl").get("lastError").get("kind").asString()).isEqualTo("PROVIDER_ERROR");

        List<JsonNode> alerts = new ArrayList<>();
        api.adminGet("mde", "/api/admin/alerts").forEach(a -> {
            if ("DETERMINISM_AVERAGING".equals(a.get("code").asString())) {
                alerts.add(a);
            }
        });
        assertThat(alerts).hasSize(1);
        JsonNode alert = alerts.getFirst();
        assertThat(alert.get("city").isNull()).as("event-wide alert").isTrue();
        assertThat(alert.get("params").get("providerId").asString()).isEqualTo("gemini");
        assertThat(alert.get("params").get("maxDiff").asDouble()).isCloseTo(expectedDiff, within(1e-9));
    }

    // ------------------------------------------------------------------ stubs (shapes from the providers' docs)

    /** Submit → COMPLETED → result with {@code has_nsfw_concepts: [false]} → CDN download. */
    private void stubFalSuccess() {
        String request = WM.baseUrl() + FAL_REQUEST;
        WM.stubFor(post(urlEqualTo("/" + props.fal().model())).willReturn(okJson("""
                {"request_id":"764cabcf-b745-4b3e-ae38-1200304cf45b","response_url":"%s","status_url":"%s/status",
                 "cancel_url":"%s/cancel","queue_position":0}""".formatted(request, request, request))));
        WM.stubFor(get(urlEqualTo(FAL_REQUEST + "/status")).willReturn(okJson("""
                {"status":"COMPLETED","request_id":"764cabcf-b745-4b3e-ae38-1200304cf45b","response_url":"%s",
                 "logs":[],"metrics":{"inference_time":0.42}}""".formatted(request))));
        WM.stubFor(get(urlEqualTo(FAL_REQUEST)).willReturn(okJson("""
                {"images":[{"url":"%s%s","width":1024,"height":1024,"content_type":"image/jpeg"}],
                 "timings":{"inference":0.42},"seed":42,"has_nsfw_concepts":[false],"prompt":"%s"}"""
                .formatted(WM.baseUrl(), FAL_IMAGE, ProbeService.PROMPT))));
        WM.stubFor(get(urlEqualTo(FAL_IMAGE))
                .willReturn(aResponse().withHeader("Content-Type", "image/jpeg").withBody(JPEG)));
    }

    /** HTTP 402: "insufficient credits or your payment method needs to be updated" (never retried). */
    private void stubBflWithoutCredits() {
        WM.stubFor(post(urlEqualTo(bflSubmit())).willReturn(aResponse().withStatus(402)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"detail\":\"Your account has insufficient credits or your payment method needs to be "
                        + "updated.\"}")));
    }

    /** The same image embedded twice gives two slightly different vectors. */
    private void stubGeminiTwoSlightlyDifferentVectors() {
        WM.stubFor(post(urlEqualTo(geminiEmbed())).inScenario("determinism").whenScenarioStateIs(STARTED)
                .willSetStateTo("second call").willReturn(okJson(embedContentResponse(FIRST))));
        WM.stubFor(post(urlEqualTo(geminiEmbed())).inScenario("determinism").whenScenarioStateIs("second call")
                .willReturn(okJson(embedContentResponse(SECOND))));
    }

    private String bflSubmit() {
        return "/v1/" + props.bfl().model();
    }

    private String geminiEmbed() {
        return "/v1beta/models/" + props.gemini().embeddingModel() + ":embedContent";
    }

    private static String embedContentResponse(double[] values) {
        StringJoiner json = new StringJoiner(",", "{\"embedding\":{\"values\":[",
                "]},\"usageMetadata\":{\"promptTokenCount\":258,"
                        + "\"promptTokenDetails\":[{\"modality\":\"IMAGE\",\"tokenCount\":258}]}}");
        for (double v : values) {
            json.add(Double.toString(v));
        }
        return json.toString();
    }

    /** 768 values shaped like a real embedding; {@code wobble} nudges every 7th one. */
    private static double[] embedding(double wobble) {
        double[] v = new double[768];
        for (int i = 0; i < v.length; i++) {
            v[i] = 0.05 * Math.sin(i + 1) + (i % 7 == 0 ? wobble : 0);
        }
        return v;
    }

    private static JsonNode imageRow(JsonNode providers, String id) {
        for (JsonNode p : providers.get("image")) {
            if (id.equals(p.get("id").asString())) {
                return p;
            }
        }
        throw new AssertionError("no image provider " + id);
    }
}
