package io.github.ewcastroh.promptbattle.scoring;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.jsonResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.not;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.ewcastroh.promptbattle.game.MutableClock;
import io.github.ewcastroh.promptbattle.generation.FakeImageGenerator;
import io.github.ewcastroh.promptbattle.generation.ProviderHttp;
import io.github.ewcastroh.promptbattle.generation.RetryPolicy;
import io.github.ewcastroh.promptbattle.scoring.EmbeddingException.Kind;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Gemini {@code embedContent} adapter against WireMock (SPEC §14.1-2, PLAN A2 "WireMock per adapter", D-013). */
class GeminiEmbeddingProviderTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    static final String KEY = "test-gemini-key";
    static final String PATH = "/v1beta/models/gemini-embedding-2:embedContent";
    static final byte[] IMAGE = FakeImageGenerator.render("a red bicycle", 7, 256, 256);
    /** Room for one retry: RetryPolicy only retries with at least 500 ms left. */
    static final Duration BUDGET = Duration.ofSeconds(3);

    /** The documented request: one inline image per call, the size inside {@code embedContentConfig}. */
    static final String REQUEST = """
            {"model":"models/gemini-embedding-2",
             "content":{"parts":[{"inlineData":{"mimeType":"image/jpeg","data":"%s"}}]},
             "embedContentConfig":{"outputDimensionality":768}}
            """.formatted(Base64.getEncoder().encodeToString(IMAGE));

    /** Per-minute limit in the google.rpc format (observed naming); {@code %s} is the RetryInfo delay. */
    static final String PER_MINUTE_429 = """
            {"error":{"code":429,"status":"RESOURCE_EXHAUSTED",
              "message":"You exceeded your current quota, please check your plan and billing details.",
              "details":[
                {"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[
                  {"quotaMetric":"generativelanguage.googleapis.com/generate_content_free_tier_requests",
                   "quotaId":"GenerateRequestsPerMinutePerProjectPerModel-FreeTier",
                   "quotaDimensions":{"model":"gemini-embedding-2","location":"global"},"quotaValue":"100"}]},
                {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"%s"}]}}
            """;

    /** Daily quota as observed on the free tier; its RetryInfo does not apply (research notes). */
    static final String DAILY_QUOTA_429 = """
            {"error":{"code":429,"status":"RESOURCE_EXHAUSTED",
              "message":"You exceeded your current quota, please check your plan and billing details.",
              "details":[
                {"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[
                  {"quotaMetric":"generativelanguage.googleapis.com/generate_content_free_tier_requests",
                   "quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier",
                   "quotaDimensions":{"model":"gemini-embedding-2","location":"global"},"quotaValue":"1000"}]},
                {"@type":"type.googleapis.com/google.rpc.Help","links":[
                  {"description":"Learn more about Gemini API quotas","url":"https://ai.google.dev/gemini-api/docs/rate-limits"}]},
                {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"1s"}]}}
            """;

    /** Observed 429 without details or Retry-After. */
    static final String BARE_429 = """
            {"error":{"code":429,"message":"Resource has been exhausted (e.g. check quota).","status":"RESOURCE_EXHAUSTED"}}
            """;

    /** The documented 400 example. */
    static final String INVALID_KEY_400 = """
            {"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT",
              "details":[
                {"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"API_KEY_INVALID","domain":"googleapis.com",
                 "metadata":{"service":"generativelanguage.googleapis.com"}},
                {"@type":"type.googleapis.com/google.rpc.LocalizedMessage","locale":"en-US",
                 "message":"API key not valid. Please pass a valid API key."}]}}
            """;

    static final String INTERNAL_500 = """
            {"error":{"code":500,"message":"An internal error has occurred.","status":"INTERNAL"}}
            """;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-12-02T15:00:00Z"));

    /** RPM 1000 and RPD 1000: far above what a test sends. Any wait for the minute window fails the test. */
    private EmbeddingRateLimiter limiter(int tpm, int estimatedTokens) {
        return new EmbeddingRateLimiter(1000, tpm, 1000, estimatedTokens, Duration.ofSeconds(65), clock,
                d -> {
                    throw new AssertionError("unexpected rate-limit wait of " + d);
                });
    }

    private EmbeddingRateLimiter generousLimiter() {
        return limiter(1_000_000, 267);
    }

    private final List<Duration> limiterWaits = new ArrayList<>();

    /** Generous limits; a wait (Google's 429 pause) moves the clock and is recorded. */
    private EmbeddingRateLimiter pausingLimiter() {
        return new EmbeddingRateLimiter(1000, 1_000_000, 1000, 267, Duration.ofSeconds(65), clock, d -> {
            limiterWaits.add(d);
            clock.advance(d);
        });
    }

    private GeminiEmbeddingProvider gemini(EmbeddingRateLimiter limiter, Duration timeout) {
        return new GeminiEmbeddingProvider(KEY, "gemini-embedding-2", 768, wm.baseUrl(), timeout,
                new ProviderHttp(), RetryPolicy.immediate(1), limiter);
    }

    private GeminiEmbeddingProvider gemini(EmbeddingRateLimiter limiter) {
        return gemini(limiter, BUDGET);
    }

    @Test
    void embedsOneInlineImageAndReturnsItsValues() {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(okJson(embedding(768, 267))));

        double[] vector = gemini(generousLimiter()).embed(IMAGE, "image/jpeg");

        assertThat(vector).containsExactly(values(768));
        // urlEqualTo pins the query too: the key goes only in the header, never as ?key=
        wm.verify(exactly(1), postRequestedFor(urlEqualTo(PATH))
                .withHeader("x-goog-api-key", equalTo(KEY))
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(equalToJson(REQUEST)) // strict: any extra field fails
                .withRequestBody(not(matchingJsonPath("$.outputDimensionality")))); // deprecated top-level field
    }

    @Test
    void theReportedTokenCountReplacesTheEstimateInTheMinuteWindow() {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(okJson(embedding(768, 267))));
        EmbeddingRateLimiter limiter = limiter(400, 100); // TPM 400, 100 tokens estimated per image

        gemini(limiter).embed(IMAGE, "image/jpeg");

        // 267 tokens counted, and the next image is expected to cost as much: 267 + 267 > 400
        // (with the estimate kept it would be 100 + 100)
        assertThat(limiter.hasCapacityNow()).isFalse();
        clock.advanceSeconds(60);
        assertThat(limiter.hasCapacityNow()).isTrue();
    }

    @Test
    void aPerMinute429IsRetriedOnceAfterItsRetryDelay() {
        stubInSequence(jsonResponse(PER_MINUTE_429.formatted("0.01s"), 429), okJson(embedding(768, 267)));
        EmbeddingRateLimiter limiter = pausingLimiter();

        assertThat(gemini(limiter).embed(IMAGE, "image/jpeg")).containsExactly(values(768));

        wm.verify(exactly(2), postRequestedFor(urlEqualTo(PATH)));
        assertThat(limiter.usedToday()).as("every request sent counts toward the day").isEqualTo(2);
        assertThat(limiterWaits).as("the limiter held requests for Google's RetryInfo hint")
                .containsExactly(Duration.ofMillis(10));
    }

    @Test
    void aRetryDelayLongerThanTheBudgetIsNotWaitedFor() {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(jsonResponse(PER_MINUTE_429.formatted("30s"), 429)));

        assertFails(() -> gemini(generousLimiter()).embed(IMAGE, "image/jpeg"), Kind.RATE_LIMITED);
        wm.verify(exactly(1), postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void aDailyQuota429IsQuotaExhaustedAndNothingMoreIsSentThatDay() {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(jsonResponse(DAILY_QUOTA_429, 429)));
        GeminiEmbeddingProvider gemini = gemini(generousLimiter());

        assertFails(() -> gemini.embed(IMAGE, "image/jpeg"), Kind.QUOTA_EXHAUSTED);
        wm.verify(exactly(1), postRequestedFor(urlEqualTo(PATH))); // no retry despite the RetryInfo
        assertThat(gemini.quotaLeft()).as("shown unhealthy in admin").isFalse();
        assertThat(gemini.hasCapacityNow()).as("prefetch skips it").isFalse();

        assertFails(() -> gemini.embed(IMAGE, "image/jpeg"), Kind.QUOTA_EXHAUSTED);
        wm.verify(exactly(1), postRequestedFor(urlEqualTo(PATH))); // refused without a request

        clock.advance(Duration.ofHours(17)); // 2026-12-03T08:00Z, midnight Pacific: a new quota day
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(okJson(embedding(768, 267))));
        assertThat(gemini.embed(IMAGE, "image/jpeg")).containsExactly(values(768));
    }

    @Test
    void theStringCodeQuotaExceededIsADailyQuotaToo() {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(jsonResponse("""
                {"error":{"code":"quota_exceeded","message":"You have exceeded your daily quota."}}
                """, 429)));
        EmbeddingRateLimiter limiter = generousLimiter();

        assertFails(() -> gemini(limiter).embed(IMAGE, "image/jpeg"), Kind.QUOTA_EXHAUSTED);
        wm.verify(exactly(1), postRequestedFor(urlEqualTo(PATH)));
        assertThat(limiter.quotaLeft()).isFalse();
    }

    @Test
    void a429WithoutDetailsIsRetriedOnceThenRateLimited() {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(jsonResponse(BARE_429, 429)));
        EmbeddingRateLimiter limiter = pausingLimiter();

        assertFails(() -> gemini(limiter).embed(IMAGE, "image/jpeg"), Kind.RATE_LIMITED);
        wm.verify(exactly(2), postRequestedFor(urlEqualTo(PATH)));
        assertThat(limiterWaits).as("without a hint, a short default pause").containsExactly(Duration.ofSeconds(1));
        assertThat(limiter.hasCapacityNow()).as("the second 429 paused it again").isFalse();
        assertThat(limiter.quotaLeft()).as("a per-minute limit leaves the day's quota").isTrue();
    }

    @Test
    void aServerErrorIsRetriedOnce() {
        stubInSequence(jsonResponse(INTERNAL_500, 500), okJson(embedding(768, 267)));

        assertThat(gemini(generousLimiter()).embed(IMAGE, "image/jpeg")).containsExactly(values(768));
        wm.verify(exactly(2), postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void aBadRequestIsAProviderErrorWithoutRetry() {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(jsonResponse(INVALID_KEY_400, 400)));

        assertFails(() -> gemini(generousLimiter()).embed(IMAGE, "image/jpeg"), Kind.PROVIDER_ERROR);
        wm.verify(exactly(1), postRequestedFor(urlEqualTo(PATH)));
    }

    static Stream<Arguments> invalidBodies() {
        return Stream.of(
                Arguments.of("malformed JSON", "{\"embedding\":{\"values\":[0.1,0.2"),
                Arguments.of("767 values", embedding(767, 267)),
                Arguments.of("769 values", embedding(769, 267)),
                Arguments.of("no embedding", "{\"usageMetadata\":{\"promptTokenCount\":267}}"),
                Arguments.of("a non-numeric value",
                        "{\"embedding\":{\"values\":[\"x\"," + joined(values(767)) + "]}}"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBodies")
    void aBodyWithoutExactly768NumbersIsAnInvalidResponse(String description, String body) {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(okJson(body)));

        assertFails(() -> gemini(generousLimiter()).embed(IMAGE, "image/jpeg"), Kind.INVALID_RESPONSE);
        wm.verify(exactly(1), postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void aResponseSlowerThanTheBudgetIsATimeout() {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(okJson(embedding(768, 267)).withFixedDelay(1000)));

        assertFails(() -> gemini(generousLimiter(), Duration.ofMillis(300)).embed(IMAGE, "image/jpeg"),
                Kind.TIMEOUT);
        wm.verify(exactly(1), postRequestedFor(urlEqualTo(PATH)));
    }

    /** A 200 body following the documented schema (the docs show no raw example). */
    static String embedding(int count, int promptTokens) {
        return """
                {"embedding":{"values":[%s]},
                 "usageMetadata":{"promptTokenCount":%d,"promptTokenDetails":[{"modality":"IMAGE","tokenCount":%d}]}}
                """.formatted(joined(values(count)), promptTokens, promptTokens);
    }

    static double[] values(int count) {
        return IntStream.range(0, count).mapToDouble(i -> ((i * 37) % 101 - 50) / 1000.0).toArray();
    }

    static String joined(double[] values) {
        return Arrays.stream(values).mapToObj(Double::toString).collect(Collectors.joining(","));
    }

    private static void stubInSequence(ResponseDefinitionBuilder first, ResponseDefinitionBuilder then) {
        wm.stubFor(post(urlEqualTo(PATH)).inScenario("sequence").whenScenarioStateIs(STARTED)
                .willSetStateTo("second").willReturn(first));
        wm.stubFor(post(urlEqualTo(PATH)).inScenario("sequence").whenScenarioStateIs("second").willReturn(then));
    }

    private static void assertFails(ThrowingCallable call, Kind kind) {
        assertThatThrownBy(call).isInstanceOfSatisfying(EmbeddingException.class, e -> {
            assertThat(e.kind()).isEqualTo(kind);
            assertThat(e.getMessage()).doesNotContain(KEY);
        });
    }
}
