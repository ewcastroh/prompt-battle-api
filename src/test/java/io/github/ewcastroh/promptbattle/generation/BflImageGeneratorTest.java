package io.github.ewcastroh.promptbattle.generation;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.ScenarioMappingBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.github.ewcastroh.promptbattle.game.FailureKind;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Black Forest Labs FLUX.2 [klein] adapter against WireMock (SPEC §5.2 and §14.1-2; PLAN A2, WireMock: BFL). */
class BflImageGeneratorTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    static final String KEY = "bfl_test-key-0000";
    static final String MODEL = "flux-2-klein-9b";
    static final String SUBMIT = "/v1/" + MODEL;
    /** BFL returns a polling_url on the region holding the task: here another host name and another path. */
    static final String POLL_HOST = "127.0.0.1";
    static final String POLL = "/regional/v1/get_result?id=t1";
    /** A signed delivery URL: its encoded signature must reach the server unchanged. */
    static final String SAMPLE = "/delivery/s.jpg?se=2026-12-02T15%3A10%3A00Z&sp=r&sig=a1b2%2Bc3d%3D";
    static final byte[] JPEG = FakeImageGenerator.render("x", 1, 256, 256);
    static final GenerationRequest REQUEST = GenerationRequest.square("a red fox in the snow", 42);
    static final Duration BUDGET = Duration.ofSeconds(10);

    private static BflImageGenerator generator(Duration timeout) {
        return new BflImageGenerator(KEY, MODEL, 24, wm.baseUrl(), 2, timeout, Duration.ofMillis(5),
                new ProviderHttp(), RetryPolicy.immediate(1));
    }

    @Test
    void submitsPollsThePollingUrlExactlyAsReturnedAndDownloadsTheSample() {
        stubSubmit();
        stubInOrder("poll", () -> get(urlEqualTo(POLL)),
                okJson(polled("Pending")), okJson(polled("Pending")), okJson(ready(sampleUrl())));
        wm.stubFor(get(urlEqualTo(SAMPLE)).willReturn(jpeg()));

        GenerationResult result = generator(BUDGET).generate(REQUEST);

        assertThat(result).isInstanceOfSatisfying(GenerationResult.Success.class, s -> {
            assertThat(s.bytes()).isEqualTo(JPEG);
            assertThat(s.mimeType()).isEqualTo("image/jpeg");
            assertThat(s.modelId()).isEqualTo(MODEL);
            assertThat(s.latencyMs()).isNotNegative();
        });
        wm.verify(1, postRequestedFor(urlEqualTo(SUBMIT))
                .withHeader("x-key", equalTo(KEY))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalToJson("""
                        {"prompt":"a red fox in the snow","seed":42,"width":1024,"height":1024,
                         "output_format":"jpeg","safety_tolerance":2}""")));
        wm.verify(3, getRequestedFor(urlEqualTo(POLL)).withHost(equalTo(POLL_HOST)));
        wm.verify(1, getRequestedFor(urlEqualTo(SAMPLE)).withoutHeader("x-key"));
        assertThat(wm.getAllServeEvents()).as("submit, 3 polls of the returned URL, 1 download").hasSize(5);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Request Moderated", "Content Moderated"})
    void moderatedTasksAreSafetyBlockedAndNothingIsDownloaded(String status) {
        stubSubmit();
        wm.stubFor(get(urlEqualTo(POLL)).willReturn(okJson("""
                {"id":"t1","status":"%s","result":null,"progress":null,
                 "details":{"Moderation Reasons":["Violence","Sexual Content","Self Harm"]},"preview":null}"""
                .formatted(status))));

        assertFailure(generator(BUDGET).generate(REQUEST), FailureKind.SAFETY_BLOCKED);
        assertThat(wm.getAllServeEvents()).as("no resubmit, no more polling, no download").hasSize(2);
    }

    @Test
    void aRateLimitedSubmitIsRetriedOnceThenSucceeds() {
        stubInOrder("submit", () -> post(urlEqualTo(SUBMIT)), tooManyActiveTasks(), okJson(submitted()));
        stubReadyAndSample();

        assertThat(generator(BUDGET).generate(REQUEST)).isInstanceOf(GenerationResult.Success.class);
        wm.verify(2, postRequestedFor(urlEqualTo(SUBMIT)));
    }

    @Test
    void aSubmitStillRateLimitedAfterItsRetryIsRateLimited() {
        wm.stubFor(post(urlEqualTo(SUBMIT)).willReturn(tooManyActiveTasks()));

        assertFailure(generator(BUDGET).generate(REQUEST), FailureKind.RATE_LIMITED);
        wm.verify(2, postRequestedFor(urlEqualTo(SUBMIT)));
        assertThat(wm.getAllServeEvents()).hasSize(2);
    }

    @Test
    void anOutOfCreditsSubmitIsAProviderErrorAndIsNeverRetried() {
        wm.stubFor(post(urlEqualTo(SUBMIT)).willReturn(aResponse().withStatus(402)
                .withHeader("Content-Type", "application/json").withBody("{\"detail\":\"Insufficient credits\"}")));

        assertFailure(generator(BUDGET).generate(REQUEST), FailureKind.PROVIDER_ERROR);
        wm.verify(1, postRequestedFor(urlEqualTo(SUBMIT)));
        assertThat(wm.getAllServeEvents()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 403})
    void anExpiredSampleIsAProviderError(int status) {
        stubSubmit();
        wm.stubFor(get(urlEqualTo(POLL)).willReturn(okJson(ready(sampleUrl()))));
        wm.stubFor(get(urlEqualTo(SAMPLE)).willReturn(aResponse().withStatus(status)));

        assertFailure(generator(BUDGET).generate(REQUEST), FailureKind.PROVIDER_ERROR);
        wm.verify(1, getRequestedFor(urlEqualTo(SAMPLE)));
        wm.verify(1, getRequestedFor(urlEqualTo(POLL)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Error", "Task not found", "Failed", "No Card Generated"})
    void failedTasksAreProviderErrors(String status) {
        stubSubmit();
        wm.stubFor(get(urlEqualTo(POLL)).willReturn(okJson(polled(status))));

        assertFailure(generator(BUDGET).generate(REQUEST), FailureKind.PROVIDER_ERROR);
        assertThat(wm.getAllServeEvents()).as("a terminal status: no more polling, no download").hasSize(2);
    }

    @Test
    void a503PollThatStillReportsPendingKeepsTheTaskAlive() {
        stubSubmit();
        // Each poll gets one retry, so the adapter itself only sees a 503 when both tries fail. Were these
        // counted as failed polls, MAX_POLL_FAILURES of them would end the call with PROVIDER_ERROR.
        int unavailable = 2 * BflImageGenerator.MAX_POLL_FAILURES;
        Stream<ResponseDefinitionBuilder> busy = IntStream.range(0, unavailable).mapToObj(i -> aResponse()
                .withStatus(503).withHeader("Content-Type", "application/json").withBody(polled("Pending")));
        stubInOrder("poll", () -> get(urlEqualTo(POLL)),
                Stream.concat(busy, Stream.of(okJson(ready(sampleUrl())))).toArray(ResponseDefinitionBuilder[]::new));
        wm.stubFor(get(urlEqualTo(SAMPLE)).willReturn(jpeg()));

        assertThat(generator(BUDGET).generate(REQUEST)).isInstanceOf(GenerationResult.Success.class);
        wm.verify(unavailable + 1, getRequestedFor(urlEqualTo(POLL)));
        wm.verify(1, postRequestedFor(urlEqualTo(SUBMIT)));
    }

    @Test
    void aTaskStillPendingWhenTheBudgetRunsOutTimesOut() {
        stubSubmit();
        wm.stubFor(get(urlEqualTo(POLL)).willReturn(okJson(polled("Pending"))));
        BflImageGenerator generator = generator(Duration.ofMillis(500));

        long started = System.nanoTime();
        GenerationResult result = generator.generate(REQUEST);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertFailure(result, FailureKind.TIMEOUT);
        assertThat(elapsedMs).as("polls until its own deadline, then stops").isBetween(450L, 3_000L);
        assertThat(wm.findAll(getRequestedFor(urlEqualTo(POLL)))).hasSizeGreaterThan(1);
        wm.verify(1, postRequestedFor(urlEqualTo(SUBMIT)));
    }

    @Test
    void oneBudgetCoversSubmitPollingAndDownload() {
        // Each step fits the 600 ms budget on its own; together they take at least 750 ms.
        wm.stubFor(post(urlEqualTo(SUBMIT)).willReturn(okJson(submitted()).withFixedDelay(250)));
        wm.stubFor(get(urlEqualTo(POLL)).willReturn(okJson(ready(sampleUrl())).withFixedDelay(250)));
        wm.stubFor(get(urlEqualTo(SAMPLE)).willReturn(jpeg().withFixedDelay(250)));

        assertFailure(generator(Duration.ofMillis(600)).generate(REQUEST), FailureKind.TIMEOUT);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"id\":\"t1\",\"polling_url\":",
            "{\"id\":\"t1\",\"status\":\"Pending\",\"webhook_url\":\"https://hooks.example.org/bfl\"}"})
    void aSubmitResponseWithoutAPollingUrlIsInvalid(String body) {
        wm.stubFor(post(urlEqualTo(SUBMIT)).willReturn(okJson(body)));

        assertFailure(generator(BUDGET).generate(REQUEST), FailureKind.INVALID_RESPONSE);
        assertThat(wm.getAllServeEvents()).as("nothing is polled").hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"id\":\"t1\",\"status\":", "{\"id\":\"t1\",\"result\":null}"})
    void aMalformedPollResponseIsInvalid(String body) {
        stubSubmit();
        wm.stubFor(get(urlEqualTo(POLL)).willReturn(okJson(body)));

        assertFailure(generator(BUDGET).generate(REQUEST), FailureKind.INVALID_RESPONSE);
        assertThat(wm.getAllServeEvents()).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "{}", "{\"sample\":\"\"}"})
    void readyWithoutASampleIsInvalid(String result) {
        stubSubmit();
        wm.stubFor(get(urlEqualTo(POLL))
                .willReturn(okJson("{\"id\":\"t1\",\"status\":\"Ready\",\"result\":" + result + "}")));

        assertFailure(generator(BUDGET).generate(REQUEST), FailureKind.INVALID_RESPONSE);
        assertThat(wm.getAllServeEvents()).as("nothing is downloaded").hasSize(2);
    }

    @Test
    void aSampleThatIsNotAnImageIsInvalid() {
        stubSubmit();
        wm.stubFor(get(urlEqualTo(POLL)).willReturn(okJson(ready(sampleUrl()))));
        // The header claims a JPEG; the bytes decide.
        wm.stubFor(get(urlEqualTo(SAMPLE)).willReturn(aResponse().withHeader("Content-Type", "image/jpeg")
                .withBody("this is not a JPEG")));

        assertFailure(generator(BUDGET).generate(REQUEST), FailureKind.INVALID_RESPONSE);
    }

    @Test
    void theKeyGoesToEveryBflCallButNeverToTheDeliveryUrl() {
        stubSubmit();
        stubInOrder("poll", () -> get(urlEqualTo(POLL)), okJson(polled("Pending")), okJson(ready(sampleUrl())));
        wm.stubFor(get(urlEqualTo(SAMPLE)).willReturn(jpeg()));

        assertThat(generator(BUDGET).generate(REQUEST)).isInstanceOf(GenerationResult.Success.class);

        wm.verify(1, postRequestedFor(urlEqualTo(SUBMIT)).withHeader("x-key", equalTo(KEY)));
        wm.verify(2, getRequestedFor(urlEqualTo(POLL)).withHeader("x-key", equalTo(KEY))
                .withHeader("Accept", equalTo("application/json")));
        List<LoggedRequest> downloads = wm.findAll(getRequestedFor(urlEqualTo(SAMPLE)));
        assertThat(downloads).singleElement().satisfies(download -> {
            assertThat(download.containsHeader("x-key")).isFalse();
            List<String> values = download.getHeaders().all().stream().flatMap(h -> h.values().stream()).toList();
            assertThat(values).as("no header of the delivery request carries the key").noneMatch(v -> v.contains(KEY));
        });
    }

    private static void assertFailure(GenerationResult result, FailureKind kind) {
        assertThat(result).isInstanceOfSatisfying(GenerationResult.Failure.class,
                f -> assertThat(f.kind()).isEqualTo(kind));
    }

    /** Serves {@code responses} one per request, in order; the last one keeps being served. */
    private static void stubInOrder(String scenario, Supplier<MappingBuilder> request,
            ResponseDefinitionBuilder... responses) {
        String state = STARTED;
        for (int i = 0; i < responses.length; i++) {
            ScenarioMappingBuilder step = request.get().inScenario(scenario).whenScenarioStateIs(state);
            if (i < responses.length - 1) {
                state = scenario + "-" + (i + 1);
                step = step.willSetStateTo(state);
            }
            wm.stubFor(step.willReturn(responses[i]));
        }
    }

    private static void stubSubmit() {
        wm.stubFor(post(urlEqualTo(SUBMIT)).willReturn(okJson(submitted())));
    }

    private static void stubReadyAndSample() {
        wm.stubFor(get(urlEqualTo(POLL)).willReturn(okJson(ready(sampleUrl()))));
        wm.stubFor(get(urlEqualTo(SAMPLE)).willReturn(jpeg()));
    }

    private static String sampleUrl() {
        return wm.baseUrl() + SAMPLE;
    }

    /** The documented {@code AsyncResponse}. */
    private static String submitted() {
        return """
                {"id":"t1","polling_url":"http://%s:%d%s","cost":1.5,"input_mp":0,"output_mp":1.05}"""
                .formatted(POLL_HOST, wm.getPort(), POLL);
    }

    /** A {@code get_result} body for any status without a result. */
    private static String polled(String status) {
        return """
                {"id":"t1","status":"%s","result":null,"progress":null,"details":null,"preview":null}"""
                .formatted(status);
    }

    private static String ready(String sample) {
        return """
                {"id":"t1","status":"Ready","result":{"sample":"%s"}}""".formatted(sample);
    }

    /** Over the account's 24 active tasks. BFL documents no 429 body; this one is from BFL's skills repo. */
    private static ResponseDefinitionBuilder tooManyActiveTasks() {
        return aResponse().withStatus(429).withHeader("Content-Type", "application/json")
                .withBody("{\"error\":\"rate_limit_exceeded\",\"message\":\"Too many concurrent requests\"}");
    }

    private static ResponseDefinitionBuilder jpeg() {
        return aResponse().withHeader("Content-Type", "image/jpeg").withBody(JPEG);
    }
}
