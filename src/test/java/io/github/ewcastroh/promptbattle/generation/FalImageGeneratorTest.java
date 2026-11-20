package io.github.ewcastroh.promptbattle.generation;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.github.ewcastroh.promptbattle.game.FailureKind;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** fal.ai queue adapter against WireMock (SPEC §5.2, §14.1-2; PLAN A2): polling, safety, retries, timeouts. */
@Timeout(10)
class FalImageGeneratorTest {

    /** fal's queue host ({@code queue.fal.run}). */
    @RegisterExtension
    static WireMockExtension queue = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    /** fal's media CDN ({@code v3b.fal.media}): a host of its own, so every request it receives can be checked. */
    @RegisterExtension
    static WireMockExtension cdn = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    static final String KEY = "test-fal-key";
    static final String AUTH = "Key " + KEY;
    static final String MODEL = "fal-ai/flux-2/klein/9b";
    static final String SUBMIT = "/" + MODEL;
    static final String REQUEST_ID = "764cabcf-b745-4b3e-ae38-1200304cf45b";
    /** Status, result and cancel URLs name only the owner and app (fal-ai/flux-2); with klein/9b fal answers 405. */
    static final String RESULT = "/fal-ai/flux-2/requests/" + REQUEST_ID;
    static final String STATUS = RESULT + "/status";
    static final String CANCEL = RESULT + "/cancel";
    static final String IMAGE = "/files/b/0a8a6905/DuNRy1OODaGrlUGEfl9SX.jpg";
    static final byte[] JPEG = FakeImageGenerator.render("x", 1, 256, 256);
    /** For calls that must not time out: ample, yet a poll that never ends still fails within seconds. */
    static final Duration BUDGET = Duration.ofSeconds(5);
    /** A queue 429 ({@code QueueSubmitError}); fal sends it only to callers that set {@code fal_max_queue_length}. */
    static final String QUEUE_FULL = "{\"request_id\":null,\"error\":\"Queue is full\"}";

    /** Warms up the HTTP client and Jackson, so the sub-second budgets below time the adapter, not class loading. */
    @BeforeAll
    static void warmUp() {
        stubHappyPath();
        generate(BUDGET);
    }

    @Test
    void submitPollsTheReturnedStatusUrlThenDownloadsTheImage() {
        queue.stubFor(post(urlEqualTo(SUBMIT)).willReturn(okJson(submitted(queue.baseUrl()))));
        queue.stubFor(get(urlEqualTo(STATUS)).inScenario("q").whenScenarioStateIs(STARTED)
                .willSetStateTo("progress").willReturn(status("IN_QUEUE")));
        queue.stubFor(get(urlEqualTo(STATUS)).inScenario("q").whenScenarioStateIs("progress")
                .willSetStateTo("done").willReturn(status("IN_PROGRESS")));
        queue.stubFor(get(urlEqualTo(STATUS)).inScenario("q").whenScenarioStateIs("done")
                .willReturn(status("COMPLETED")));
        stubResult(okJson(output(false)));
        stubImage(aResponse().withHeader("Content-Type", "image/jpeg").withBody(JPEG));

        GenerationResult result = generate(Duration.ofSeconds(10));

        assertThat(result).isInstanceOfSatisfying(GenerationResult.Success.class, s -> {
            assertThat(s.bytes()).isEqualTo(JPEG);
            assertThat(s.mimeType()).isEqualTo("image/jpeg");
            assertThat(s.modelId()).isEqualTo(MODEL);
        });
        queue.verify(postRequestedFor(urlEqualTo(SUBMIT))
                .withHeader("Authorization", equalTo(AUTH))
                .withHeader("Content-Type", containing("application/json"))
                .withHeader("x-app-fal-disable-fallback", equalTo("true"))
                .withRequestBody(equalToJson("""
                        {"prompt":"a cat","seed":7,"image_size":{"width":1024,"height":1024},"num_inference_steps":4,
                         "num_images":1,"output_format":"jpeg","enable_safety_checker":true}""")));
        queue.verify(3, getRequestedFor(urlEqualTo(STATUS)).withHeader("Authorization", equalTo(AUTH)));
        queue.verify(1, getRequestedFor(urlEqualTo(RESULT)).withHeader("Authorization", equalTo(AUTH)));
        cdn.verify(getRequestedFor(urlEqualTo(IMAGE)).withoutHeader("Authorization"));
        // fal drops a request that could not start in time: whole seconds, positive, inside our 10 s budget
        String startTimeout = queue.findAll(postRequestedFor(urlEqualTo(SUBMIT))).getFirst()
                .getHeader("X-Fal-Request-Start-Timeout");
        assertThat(startTimeout).matches("[1-9][0-9]*");
        assertThat(Long.parseLong(startTimeout)).isLessThan(10);
    }

    @Test
    void statusAndResultAreFetchedOnlyFromTheUrlsReturnedAtSubmit() {
        // Same server under another host name: a URL rebuilt from the configured queue URL (localhost) or from
        // the model id (/fal-ai/flux-2/klein/9b/requests/...) would find no stub.
        String returned = "http://127.0.0.1:" + queue.getPort();
        queue.stubFor(post(urlEqualTo(SUBMIT)).willReturn(okJson(submitted(returned))));
        queue.stubFor(get(urlEqualTo(STATUS)).withHost(equalTo("127.0.0.1"))
                .willReturn(status("COMPLETED", returned)));
        queue.stubFor(get(urlEqualTo(RESULT)).withHost(equalTo("127.0.0.1")).willReturn(okJson(output(false))));
        stubImage(aResponse().withBody(JPEG));

        assertThat(generate(BUDGET)).isInstanceOf(GenerationResult.Success.class);

        assertThat(queue.getAllServeEvents()).map(ServeEvent::getRequest)
                .map(r -> r.getMethod() + " " + r.getHost() + r.getUrl())
                .containsExactlyInAnyOrder("POST localhost" + SUBMIT, "GET 127.0.0.1" + STATUS,
                        "GET 127.0.0.1" + RESULT);
    }

    @Test
    void flaggedImageIsSafetyBlockedAndNeverDownloaded() {
        stubHappyPath();
        stubResult(okJson(output(true)));

        assertThat(failureKind(generate(BUDGET))).isEqualTo(FailureKind.SAFETY_BLOCKED);
        assertThat(cdn.getAllServeEvents()).isEmpty();
    }

    @Test
    void contentPolicyViolationIsSafetyBlocked() {
        stubHappyPath();
        stubResult(json(422, """
                {"detail":[{"loc":["body","prompt"],
                  "msg":"The content could not be processed because it contained material \
                flagged by a content checker.",
                  "type":"content_policy_violation","url":"https://docs.fal.ai/errors#content_policy_violation",
                  "input":"a prompt containing forbidden content","categories":["sexual"]}]}"""));

        assertThat(failureKind(generate(BUDGET))).isEqualTo(FailureKind.SAFETY_BLOCKED);
        queue.verify(1, getRequestedFor(urlEqualTo(RESULT)));
        assertThat(cdn.getAllServeEvents()).isEmpty();
    }

    @Test
    void submit429IsRetriedOnceThenSucceeds() {
        stubHappyPath();
        failFirstSubmitWith(json(429, QUEUE_FULL));

        assertThat(generate(BUDGET)).isInstanceOf(GenerationResult.Success.class);
        queue.verify(2, postRequestedFor(urlEqualTo(SUBMIT)));
    }

    @Test
    void submit500IsRetriedOnceThenSucceeds() {
        stubHappyPath();
        failFirstSubmitWith(json(500, "{\"detail\":\"An internal error occurred. Retry the request.\"}"));

        assertThat(generate(BUDGET)).isInstanceOf(GenerationResult.Success.class);
        queue.verify(2, postRequestedFor(urlEqualTo(SUBMIT)));
    }

    @Test
    void submit429TwiceIsRateLimitedAfterOneRetry() {
        stubHappyPath();
        queue.stubFor(post(urlEqualTo(SUBMIT)).willReturn(json(429, QUEUE_FULL)));

        assertThat(failureKind(generate(BUDGET))).isEqualTo(FailureKind.RATE_LIMITED);
        queue.verify(2, postRequestedFor(urlEqualTo(SUBMIT)));
        queue.verify(0, getRequestedFor(anyUrl()));
    }

    @Test
    void result500WithNeedsRetryZeroIsAProviderErrorWithoutRetry() {
        stubHappyPath();
        stubResult(json(500, """
                {"detail":[{"loc":["body"],"msg":"Internal server error","type":"internal_server_error",
                  "url":"https://docs.fal.ai/errors#internal_server_error"}]}""").withHeader("X-Fal-Needs-Retry", "0"));

        assertThat(failureKind(generate(BUDGET))).isEqualTo(FailureKind.PROVIDER_ERROR);
        queue.verify(1, getRequestedFor(urlEqualTo(RESULT)));
    }

    @Test
    void completedWithARunnerErrorIsAProviderError() {
        stubHappyPath();
        stubStatus(completedWithError("runner_server_error"));
        stubResult(json(500, "{\"detail\":\"Internal server error\",\"error_type\":\"runner_server_error\"}")
                .withHeader("X-Fal-Error-Type", "runner_server_error"));

        assertThat(failureKind(generate(BUDGET))).isEqualTo(FailureKind.PROVIDER_ERROR);
        queue.verify(2, getRequestedFor(urlEqualTo(RESULT))); // one retry: no X-Fal-Needs-Retry: 0 forbids it
        assertThat(cdn.getAllServeEvents()).isEmpty();
    }

    @Test
    void startupTimeoutIsATimeout() {
        stubHappyPath();
        stubStatus(completedWithError("startup_timeout"));
        stubResult(json(504, "{\"detail\":\"Request timed out\",\"error_type\":\"startup_timeout\"}")
                .withHeader("X-Fal-Request-Timeout-Type", "user").withHeader("X-Fal-Error-Type", "startup_timeout"));

        assertThat(failureKind(generate(BUDGET))).isEqualTo(FailureKind.TIMEOUT);
        assertThat(cdn.getAllServeEvents()).isEmpty();
    }

    @Test
    void pollingPastTheBudgetTimesOutAndCancelsTheRequest() {
        stubHappyPath();
        stubStatus(status("IN_PROGRESS"));
        Duration budget = Duration.ofMillis(500);

        long started = System.nanoTime();
        GenerationResult result = generate(budget);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(failureKind(result)).isEqualTo(FailureKind.TIMEOUT);
        assertThat(elapsed).isBetween(budget, budget.plusSeconds(1));
        assertThat(queue.findAll(getRequestedFor(urlEqualTo(STATUS)))).hasSizeGreaterThan(1);
        queue.verify(1, putRequestedFor(urlEqualTo(CANCEL)).withHeader("Authorization", equalTo(AUTH)));
        queue.verify(0, getRequestedFor(urlEqualTo(RESULT)));
    }

    @Test
    void submitSlowerThanTheBudgetTimesOut() {
        queue.stubFor(post(urlEqualTo(SUBMIT)).willReturn(okJson(submitted(queue.baseUrl())).withFixedDelay(1500)));

        long started = System.nanoTime();
        GenerationResult result = generate(Duration.ofMillis(300));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(failureKind(result)).isEqualTo(FailureKind.TIMEOUT);
        assertThat(elapsed).isLessThan(Duration.ofMillis(1200)); // gave up at the deadline, not at the late answer
        queue.verify(1, postRequestedFor(urlEqualTo(SUBMIT))); // no time is left for a retry
        queue.verify(0, getRequestedFor(anyUrl()));
        queue.verify(0, putRequestedFor(anyUrl())); // no request id yet: nothing to cancel
    }

    @Test
    void oneDeadlineCoversSubmitAndPolling() {
        stubHappyPath();
        queue.stubFor(post(urlEqualTo(SUBMIT)).willReturn(okJson(submitted(queue.baseUrl())).withFixedDelay(400)));
        stubStatus(status("IN_PROGRESS"));
        Duration budget = Duration.ofMillis(600);

        long started = System.nanoTime();
        GenerationResult result = generate(budget);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(failureKind(result)).isEqualTo(FailureKind.TIMEOUT);
        // polling only got what the slow submit left; a budget of its own would end after 400 + 600 ms
        assertThat(elapsed).isBetween(budget, Duration.ofMillis(900));
    }

    @Test
    void malformedSubmitJsonIsAnInvalidResponse() {
        stubHappyPath();
        queue.stubFor(post(urlEqualTo(SUBMIT))
                .willReturn(okJson("{\"request_id\":\"" + REQUEST_ID + "\",\"status_url\":")));

        assertThat(failureKind(generate(BUDGET))).isEqualTo(FailureKind.INVALID_RESPONSE);
        queue.verify(0, getRequestedFor(anyUrl()));
    }

    @Test
    void malformedStatusJsonIsAnInvalidResponse() {
        stubHappyPath();
        stubStatus(json(202, "{\"status\":\"IN_PROGRESS\",\"request_id\":"));

        assertThat(failureKind(generate(BUDGET))).isEqualTo(FailureKind.INVALID_RESPONSE);
        queue.verify(0, getRequestedFor(urlEqualTo(RESULT)));
    }

    @Test
    void malformedResultJsonIsAnInvalidResponse() {
        stubHappyPath();
        stubResult(okJson("{\"images\":[{\"url\":"));

        assertThat(failureKind(generate(BUDGET))).isEqualTo(FailureKind.INVALID_RESPONSE);
        assertThat(cdn.getAllServeEvents()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            """
            {"images":[],"timings":{},"seed":7,"has_nsfw_concepts":[],"prompt":"a cat"}""",
            """
            {"timings":{},"seed":7,"has_nsfw_concepts":[false],"prompt":"a cat"}""",
            """
            {"images":[{"content_type":"image/jpeg"}],"timings":{},"seed":7,"has_nsfw_concepts":[false],
             "prompt":"a cat"}"""})
    void resultWithoutAnImageUrlIsAnInvalidResponse(String body) {
        stubHappyPath();
        stubResult(okJson(body));

        assertThat(failureKind(generate(BUDGET))).isEqualTo(FailureKind.INVALID_RESPONSE);
        assertThat(cdn.getAllServeEvents()).isEmpty();
    }

    @Test
    void htmlFromTheCdnIsAnInvalidResponse() {
        stubHappyPath();
        stubImage(aResponse().withHeader("Content-Type", "text/html")
                .withBody("<!doctype html><html><body>Access denied</body></html>"));

        assertThat(failureKind(generate(BUDGET))).isEqualTo(FailureKind.INVALID_RESPONSE);
    }

    @Test
    void missingCdnFileIsAProviderError() {
        stubHappyPath();
        stubImage(aResponse().withStatus(404).withBody("Not Found"));

        assertThat(failureKind(generate(BUDGET))).isEqualTo(FailureKind.PROVIDER_ERROR);
        cdn.verify(1, getRequestedFor(urlEqualTo(IMAGE)));
    }

    @Test
    void unknownRequestAtStatusIsAProviderError() {
        stubHappyPath();
        stubStatus(json(404, "{\"status\":\"NOT_FOUND\"}"));

        assertThat(failureKind(generate(BUDGET))).isEqualTo(FailureKind.PROVIDER_ERROR);
        queue.verify(1, getRequestedFor(urlEqualTo(STATUS))); // permanent: neither retried nor polled again
        queue.verify(0, getRequestedFor(urlEqualTo(RESULT)));
    }

    @Test
    void rejectedKeyAtSubmitIsAProviderErrorWithoutRetry() {
        stubHappyPath();
        queue.stubFor(post(urlEqualTo(SUBMIT))
                .willReturn(json(401, "{\"detail\":\"Invalid authentication credentials\"}")));

        assertThat(failureKind(generate(BUDGET))).isEqualTo(FailureKind.PROVIDER_ERROR);
        queue.verify(1, postRequestedFor(urlEqualTo(SUBMIT)));
        queue.verify(0, getRequestedFor(anyUrl()));
    }

    @Test
    void imageDownloadNeverCarriesTheKey() {
        stubHappyPath();

        assertThat(generate(BUDGET)).isInstanceOf(GenerationResult.Success.class);

        List<LoggedRequest> downloads = cdn.getAllServeEvents().stream().map(ServeEvent::getRequest).toList();
        assertThat(downloads).hasSize(1).allSatisfy(download -> {
            assertThat(download.containsHeader("Authorization")).isFalse();
            assertThat(download.getHeaders().all())
                    .allSatisfy(header -> assertThat(header.values()).noneMatch(v -> v.contains(KEY)));
            assertThat(download.getAbsoluteUrl()).doesNotContain(KEY);
        });
    }

    private static FalImageGenerator generator(Duration timeout) {
        return new FalImageGenerator(KEY, MODEL, 10, queue.baseUrl(), timeout, Duration.ofMillis(10),
                new ProviderHttp(), RetryPolicy.immediate(1), Runnable::run); // cancels inline: observable at once
    }

    private static GenerationResult generate(Duration timeout) {
        return generator(timeout).generate(GenerationRequest.square("a cat", 7));
    }

    private static FailureKind failureKind(GenerationResult result) {
        assertThat(result).isInstanceOf(GenerationResult.Failure.class);
        return ((GenerationResult.Failure) result).kind();
    }

    private static ResponseDefinitionBuilder json(int status, String body) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body);
    }

    /** The submit answer (API reference {@code SubmitResponse}), its request URLs under {@code base}. */
    private static String submitted(String base) {
        String request = base + RESULT;
        return """
                {"status":"IN_QUEUE","request_id":"%s","response_url":"%s","status_url":"%s/status",
                 "cancel_url":"%s/cancel","queue_position":0,"logs":null,"metrics":{}}"""
                .formatted(REQUEST_ID, request, request, request);
    }

    /** GET status_url: 202 while IN_QUEUE or IN_PROGRESS, 200 once COMPLETED. */
    private static ResponseDefinitionBuilder status(String status) {
        return status(status, queue.baseUrl());
    }

    private static ResponseDefinitionBuilder status(String status, String base) {
        return json("COMPLETED".equals(status) ? 200 : 202, """
                {"status":"%s","request_id":"%s","response_url":"%s","logs":null}"""
                .formatted(status, REQUEST_ID, base + RESULT));
    }

    /** A failed request is COMPLETED too, with {@code error} and {@code error_type}; the result tells what failed. */
    private static ResponseDefinitionBuilder completedWithError(String errorType) {
        return json(200, """
                {"status":"COMPLETED","request_id":"%s","response_url":"%s","logs":null,"metrics":{},
                 "error":"Request failed","error_type":"%s"}"""
                .formatted(REQUEST_ID, queue.url(RESULT), errorType));
    }

    /** A {@code Klein9BDistilledT2IOutput} with one image on the CDN; a flagged image is black. */
    private static String output(boolean nsfw) {
        return """
                {"images":[{"url":"%s","width":1024,"height":1024,"content_type":"image/jpeg"}],
                 "timings":{"inference":0.41},"seed":7,"has_nsfw_concepts":[%s],"prompt":"a cat"}"""
                .formatted(cdn.url(IMAGE), nsfw);
    }

    /** Submit, COMPLETED, result, image (and cancel); each failure test replaces one step: the newest stub wins. */
    private static void stubHappyPath() {
        queue.stubFor(post(urlEqualTo(SUBMIT)).willReturn(okJson(submitted(queue.baseUrl()))));
        stubStatus(status("COMPLETED"));
        stubResult(okJson(output(false)));
        stubImage(aResponse().withHeader("Content-Type", "image/jpeg").withBody(JPEG));
        queue.stubFor(put(urlEqualTo(CANCEL)).willReturn(json(202, "{\"status\":\"CANCELLATION_REQUESTED\"}")));
    }

    /** Only the first submit gets {@code response}; the retry falls through to the happy path's answer. */
    private static void failFirstSubmitWith(ResponseDefinitionBuilder response) {
        queue.stubFor(post(urlEqualTo(SUBMIT)).inScenario("submit").whenScenarioStateIs(STARTED)
                .willSetStateTo("failed once").willReturn(response));
    }

    private static void stubStatus(ResponseDefinitionBuilder response) {
        queue.stubFor(get(urlEqualTo(STATUS)).willReturn(response));
    }

    private static void stubResult(ResponseDefinitionBuilder response) {
        queue.stubFor(get(urlEqualTo(RESULT)).willReturn(response));
    }

    private static void stubImage(ResponseDefinitionBuilder response) {
        cdn.stubFor(get(urlEqualTo(IMAGE)).willReturn(response));
    }
}
