package io.github.ewcastroh.promptbattle.generation;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.concurrent.Executors;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Thin HTTP client for the provider adapters (SPEC §5.2: Spring {@code RestClient}). Connect timeout 5 s;
 * each call's timeout is the time left until its {@link Deadline} (covering the response body too), so a whole
 * generation (submit, polling, download) never exceeds its budget. 4xx/5xx come back as a {@link Response};
 * only a call without any response throws {@link CallFailure}. Never logs URLs, headers or bodies (keys).
 */
public final class ProviderHttp {

    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    /** Larger bodies are cut off and reported as invalid (an image is a few MB at most). */
    public static final int MAX_BODY_BYTES = 25 * 1024 * 1024;

    /** A raw HTTP response. {@code truncated} when the body exceeded {@link #MAX_BODY_BYTES}. */
    public record Response(int status, HttpHeaders headers, byte[] body, boolean truncated) {

        public boolean ok() {
            return status >= 200 && status < 300;
        }

        public String header(String name) {
            return headers.getFirst(name);
        }

        public String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    /** A call that got no HTTP response: the deadline passed, the thread was interrupted, or an I/O error. */
    public static final class CallFailure extends Exception {

        private final boolean timeout;

        CallFailure(boolean timeout, String message) {
            super(message, null, false, false);
            this.timeout = timeout;
        }

        public boolean timeout() {
            return timeout;
        }
    }

    private static final ThreadLocal<Duration> CALL_TIMEOUT = new ThreadLocal<>();

    private final RestClient client;

    public ProviderHttp() {
        this(CONNECT_TIMEOUT);
    }

    public ProviderHttp(Duration connectTimeout) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
        ClientHttpRequestFactory perCallTimeout = (uri, method) -> {
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
            Duration timeout = CALL_TIMEOUT.get();
            if (timeout != null) {
                factory.setReadTimeout(timeout);
            }
            return factory.createRequest(uri, method);
        };
        this.client = RestClient.builder().requestFactory(perCallTimeout).build();
    }

    public Response get(URI uri, Map<String, String> headers, Deadline deadline) throws CallFailure {
        return send(HttpMethod.GET, uri, headers, null, deadline);
    }

    public Response postJson(URI uri, Map<String, String> headers, byte[] json, Deadline deadline)
            throws CallFailure {
        return send(HttpMethod.POST, uri, headers, json, deadline);
    }

    public Response put(URI uri, Map<String, String> headers, Deadline deadline) throws CallFailure {
        return send(HttpMethod.PUT, uri, headers, null, deadline);
    }

    private Response send(HttpMethod method, URI uri, Map<String, String> headers, byte[] json, Deadline deadline)
            throws CallFailure {
        Duration remaining = deadline.remaining();
        if (remaining.toMillis() <= 0) {
            throw new CallFailure(true, "deadline passed");
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new CallFailure(false, "interrupted");
        }
        CALL_TIMEOUT.set(remaining);
        try {
            RestClient.RequestBodySpec spec = client.method(method).uri(uri).headers(h -> headers.forEach(h::set));
            if (json != null) {
                spec = spec.contentType(MediaType.APPLICATION_JSON).body(json);
            }
            return spec.exchange((request, response) -> {
                byte[] body;
                boolean truncated;
                try (InputStream in = response.getBody()) {
                    body = in.readNBytes(MAX_BODY_BYTES + 1);
                }
                truncated = body.length > MAX_BODY_BYTES;
                return new Response(response.getStatusCode().value(), HttpHeaders.copyOf(response.getHeaders()),
                        body, truncated);
            });
        } catch (ResourceAccessException e) {
            // A connect timeout (5 s) with budget left is a connection error, worth a retry: nothing was sent.
            // Only a call that ran out of its deadline is a timeout.
            boolean timeout = deadline.expired() || (isTimeout(e) && !isConnectTimeout(e));
            throw new CallFailure(timeout, e.getClass().getSimpleName());
        } catch (RuntimeException e) {
            // e.g. an invalid URI or a failure inside the client: no usable response
            throw new CallFailure(deadline.expired(), e.getClass().getSimpleName());
        } finally {
            CALL_TIMEOUT.remove();
        }
    }

    private static boolean isConnectTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof HttpConnectTimeoutException || t instanceof java.net.ConnectException) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof HttpTimeoutException || t instanceof java.net.SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    /** One HTTP call, as a lambda. */
    @FunctionalInterface
    public interface HttpCall {
        Response call() throws CallFailure;
    }

    /** Decides whether a response is worth a retry, and the server's wait hint for it (null = none). */
    public interface RetryRule {
        boolean retryable(Response response);

        Duration hint(Response response);
    }

    /** 429 and 5xx are retryable; the hint is {@code Retry-After}. */
    public static final RetryRule TRANSIENT = new RetryRule() {
        @Override
        public boolean retryable(Response r) {
            return r.status() == 429 || r.status() >= 500;
        }

        @Override
        public Duration hint(Response r) {
            return retryAfter(r);
        }
    };

    /**
     * Runs a call with the policy's retries for retryable responses and connection errors (SPEC §5.4: one
     * automatic retry with jittered backoff). Returns the last response, retryable or not. Throws when the
     * call never got a response: on a timeout at once (no time is left), on a connection error once the
     * retries are used up.
     */
    public static Response withRetry(HttpCall call, RetryPolicy retry, RetryRule rule, Deadline deadline)
            throws CallFailure {
        for (int retryNo = 1; ; retryNo++) {
            Response response;
            try {
                response = call.call();
            } catch (CallFailure e) {
                if (e.timeout()) {
                    throw e;
                }
                Duration delay = retry.delay(retryNo, null, deadline);
                if (delay == null || !deadline.sleep(delay)) {
                    throw e;
                }
                continue;
            }
            if (rule.retryable(response)) {
                Duration delay = retry.delay(retryNo, rule.hint(response), deadline);
                if (delay != null && deadline.sleep(delay)) {
                    continue;
                }
            }
            return response;
        }
    }

    /**
     * Server hint from {@code Retry-After} (seconds or an HTTP date), or null. The caller still caps it by its
     * deadline.
     */
    public static Duration retryAfter(Response response) {
        String value = response.header(HttpHeaders.RETRY_AFTER);
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.strip();
        try {
            return Duration.ofSeconds(Math.max(0, Long.parseLong(v)));
        } catch (NumberFormatException e) {
            try {
                ZonedDateTime at = ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME);
                Duration d = Duration.between(ZonedDateTime.now(at.getZone()), at);
                return d.isNegative() ? Duration.ZERO : d;
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }

    /** True when the URL is an absolute http(s) URL (provider responses carry status, result and image URLs). */
    public static boolean isHttpUrl(String url) {
        if (url == null) {
            return false;
        }
        try {
            URI uri = URI.create(url);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Reads a JPEG or PNG signature (the adapters reject anything else as INVALID_RESPONSE). */
    public static String imageType(byte[] bytes) {
        if (bytes != null && bytes.length > 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) {
            return "image/jpeg";
        }
        if (bytes != null && bytes.length > 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N'
                && bytes[3] == 'G') {
            return "image/png";
        }
        return null;
    }
}
