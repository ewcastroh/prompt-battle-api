package io.github.ewcastroh.promptbattle.scoring;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.jsonResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.ewcastroh.promptbattle.generation.FakeImageGenerator;
import io.github.ewcastroh.promptbattle.generation.ProviderHttp;
import io.github.ewcastroh.promptbattle.generation.RetryPolicy;
import io.github.ewcastroh.promptbattle.scoring.EmbeddingException.Kind;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/** Cohere Embed v2 adapter against WireMock (SPEC §14.1-2, PLAN A2 "WireMock per adapter", D-011). */
class CohereEmbeddingProviderTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    static final String KEY = "test-cohere-key";
    static final String PATH = "/v2/embed";
    static final byte[] IMAGE = FakeImageGenerator.render("a red bicycle", 7, 256, 256);
    /** Room for one retry: RetryPolicy only retries with at least 500 ms left. */
    static final Duration BUDGET = Duration.ofSeconds(3);

    /** One image as an {@code inputs[].content[]} data URI, valid under every documented input_type rule. */
    static final String REQUEST = """
            {"model":"embed-v4.0","input_type":"search_document",
             "inputs":[{"content":[{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,%s"}}]}],
             "embedding_types":["float"],"output_dimension":1024}
            """.formatted(Base64.getEncoder().encodeToString(IMAGE));

    private CohereEmbeddingProvider cohere(Duration timeout) {
        return new CohereEmbeddingProvider(KEY, "embed-v4.0", 1024, wm.baseUrl(), timeout, new ProviderHttp(),
                RetryPolicy.immediate(1));
    }

    @Test
    void embedsOneImageAsADataUriAndReturnsTheFirstFloatVector() {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(okJson(embeddings(1024))));

        double[] vector = cohere(BUDGET).embed(IMAGE, "image/jpeg");

        assertThat(vector).containsExactly(values(1024));
        wm.verify(exactly(1), postRequestedFor(urlEqualTo(PATH))
                .withHeader("Authorization", equalTo("Bearer " + KEY))
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(equalToJson(REQUEST))); // strict: any extra field fails
    }

    @Test
    void a429IsRetriedOnceThenSucceeds() {
        wm.stubFor(post(urlEqualTo(PATH)).inScenario("sequence").whenScenarioStateIs(STARTED)
                .willSetStateTo("second").willReturn(jsonResponse(error(
                        "You are past the per minute request limit, please wait and try again later."), 429)));
        wm.stubFor(post(urlEqualTo(PATH)).inScenario("sequence").whenScenarioStateIs("second")
                .willReturn(okJson(embeddings(1024))));

        assertThat(cohere(BUDGET).embed(IMAGE, "image/jpeg")).containsExactly(values(1024));
        wm.verify(exactly(2), postRequestedFor(urlEqualTo(PATH)));
    }

    @ParameterizedTest(name = "HTTP {0} → {1} after {2} request(s)")
    @CsvSource({
            "400, PROVIDER_ERROR,  1",
            "401, PROVIDER_ERROR,  1",
            "402, QUOTA_EXHAUSTED, 1",
            "429, RATE_LIMITED,    2",
            "500, PROVIDER_ERROR,  2",
            "503, PROVIDER_ERROR,  2",
            "504, TIMEOUT,         2" })
    void errorStatusesMapToAFailureKind(int status, Kind kind, int requests) {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(jsonResponse(error("HTTP " + status), status)));

        assertFails(() -> cohere(BUDGET).embed(IMAGE, "image/jpeg"), kind);
        wm.verify(exactly(requests), postRequestedFor(urlEqualTo(PATH)));
    }

    static Stream<Arguments> invalidBodies() {
        return Stream.of(
                Arguments.of("malformed JSON", "{\"embeddings\":{\"float\":[[0.1,0.2"),
                Arguments.of("1023 values", embeddings(1023)),
                Arguments.of("no float embeddings", "{\"id\":\"e-1\",\"embeddings\":{},\"texts\":[]}"),
                Arguments.of("an empty float list", "{\"id\":\"e-1\",\"embeddings\":{\"float\":[]}}"),
                Arguments.of("a non-numeric value",
                        "{\"id\":\"e-1\",\"embeddings\":{\"float\":[[\"x\"," + joined(values(1023)) + "]]}}"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBodies")
    void aBodyWithoutExactly1024FloatsIsAnInvalidResponse(String description, String body) {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(okJson(body)));

        assertFails(() -> cohere(BUDGET).embed(IMAGE, "image/jpeg"), Kind.INVALID_RESPONSE);
        wm.verify(exactly(1), postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void aResponseSlowerThanTheBudgetIsATimeout() {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(okJson(embeddings(1024)).withFixedDelay(1000)));

        assertFails(() -> cohere(Duration.ofMillis(300)).embed(IMAGE, "image/jpeg"), Kind.TIMEOUT);
        wm.verify(exactly(1), postRequestedFor(urlEqualTo(PATH)));
    }

    /** The reference's "Images" response shape with {@code count} floats. */
    static String embeddings(int count) {
        return """
                {"id":"5807ee2e-0cda-445a-9ec8-864c60a06606","embeddings":{"float":[[%s]]},"texts":[],
                 "images":[{"width":256,"height":256,"format":"jpeg","bit_depth":24}],
                 "meta":{"api_version":{"version":"2"},"billed_units":{"images":1}}}
                """.formatted(joined(values(count)));
    }

    /** Every Cohere error body: an optional message and id. */
    static String error(String message) {
        return "{\"id\":\"0b5bd0e7-5f4a-4f0e-8d0e-6a3f2a7c1d42\",\"message\":\"" + message + "\"}";
    }

    static double[] values(int count) {
        return IntStream.range(0, count).mapToDouble(i -> ((i * 37) % 101 - 50) / 1000.0).toArray();
    }

    static String joined(double[] values) {
        return Arrays.stream(values).mapToObj(Double::toString).collect(Collectors.joining(","));
    }

    private static void assertFails(ThrowingCallable call, Kind kind) {
        assertThatThrownBy(call).isInstanceOfSatisfying(EmbeddingException.class, e -> {
            assertThat(e.kind()).isEqualTo(kind);
            assertThat(e.getMessage()).doesNotContain(KEY);
        });
    }
}
