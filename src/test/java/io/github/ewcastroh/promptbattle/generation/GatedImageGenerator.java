package io.github.ewcastroh.promptbattle.generation;

import static org.awaitility.Awaitility.await;

import io.github.ewcastroh.promptbattle.game.FailureKind;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A test image provider whose calls block until the test releases them by prompt, so the test decides when
 * each provider slot frees. It records the prompts in call order and the most calls it ever had in flight.
 */
final class GatedImageGenerator implements ImageGenerator {

    /** A call nobody releases gives up after this, so a broken test fails instead of hanging. */
    private static final Duration GATE_TIMEOUT = Duration.ofSeconds(5);

    private final String id;
    private final int maxConcurrency;
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final Map<String, CountDownLatch> gates = new HashMap<>(); // guarded by this
    private final Map<String, GenerationResult> results = new ConcurrentHashMap<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();
    private boolean open; // guarded by this
    private volatile Duration hold = Duration.ZERO;

    GatedImageGenerator(String id, int maxConcurrency) {
        this.id = id;
        this.maxConcurrency = maxConcurrency;
    }

    @Override
    public String providerId() {
        return id;
    }

    @Override
    public String modelId() {
        return id + "-model";
    }

    @Override
    public int maxConcurrency() {
        return maxConcurrency;
    }

    @Override
    public GenerationResult generate(GenerationRequest request) {
        String prompt = request.prompt();
        maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
        calls.add(prompt);
        try {
            if (!gate(prompt).await(GATE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                return new GenerationResult.Failure(FailureKind.TIMEOUT, "never released");
            }
            Duration pause = hold;
            if (!pause.isZero()) {
                Thread.sleep(pause);
            }
            return results.getOrDefault(prompt,
                    new GenerationResult.Success(new byte[] {1, 2, 3}, "image/jpeg", modelId(), 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new GenerationResult.Failure(FailureKind.PROVIDER_ERROR, "interrupted");
        } finally {
            inFlight.decrementAndGet();
        }
    }

    /** Lets the call with this prompt return, now or as soon as it arrives. */
    void release(String prompt) {
        gate(prompt).countDown();
    }

    /** Lets every current and later call return at once. */
    void releaseAll() {
        releaseAll(Duration.ZERO);
    }

    /** Lets every current and later call return, each after holding its slot for {@code hold}. */
    synchronized void releaseAll(Duration hold) {
        this.hold = hold;
        open = true;
        gates.values().forEach(CountDownLatch::countDown);
    }

    /** The result the call with this prompt returns (default: a success). */
    void respond(String prompt, GenerationResult result) {
        results.put(prompt, result);
    }

    /** Prompts in the order the calls arrived. */
    List<String> calls() {
        return List.copyOf(calls);
    }

    int inFlight() {
        return inFlight.get();
    }

    /** The most calls this provider ever had in flight at once. */
    int maxInFlight() {
        return maxInFlight.get();
    }

    /** Waits until this provider has received at least {@code count} calls. */
    void awaitCalls(int count) {
        await().atMost(Duration.ofMillis(800)).pollInterval(Duration.ofMillis(5))
                .until(() -> calls.size() >= count);
    }

    private synchronized CountDownLatch gate(String prompt) {
        return gates.computeIfAbsent(prompt, p -> new CountDownLatch(open ? 0 : 1));
    }
}
