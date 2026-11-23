package io.github.ewcastroh.promptbattle.generation;

import io.github.ewcastroh.promptbattle.game.CircuitState;
import io.github.ewcastroh.promptbattle.game.FailureKind;
import io.github.ewcastroh.promptbattle.game.QueuePositions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The generation FIFO shared by both cities and the per-provider concurrency limits (SPEC §5.5).
 * <p>One dispatcher thread scans the FIFO in order and starts the first job that can run now, holding one of
 * its provider's slots from start to finish (submit, provider-side queue, polling and download), so a
 * provider never has more calls in flight than its {@code maxConcurrency}:
 * <ul>
 * <li>A player's attempt ({@link Target.Flexible}) goes to the active provider; when that one is full and it
 * is listed in IMAGE_LOAD_SPLIT, to the next listed provider with a free slot (D-012, D-023). Attempts have
 * the same candidates, so they start in strict FIFO order across both cities.</li>
 * <li>Calibration anchors, previews and probes ({@link Target.Pinned}) run on one provider; a later attempt
 * may start before a pinned job that waits for its own provider, never the other way round.</li>
 * <li>A provider whose circuit is open takes nothing; a half-open one takes a single trial. Pinned jobs on
 * an open circuit fail at once (probes ignore the circuit). When no candidate is closed the job may use
 * any chain provider that accepts a call, until the domain switches the active provider.</li>
 * </ul>
 * Lock order: this dispatcher's lock may be held while reading a circuit breaker, never the reverse; no
 * callback, provider call or future completion runs while the lock is held.
 */
public final class GenerationDispatcher implements QueuePositions, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GenerationDispatcher.class);

    /** How often a dispatcher with waiting jobs re-checks time-based circuit transitions. */
    static final long RECHECK_MILLIS = 200;

    /** Where a job may run. */
    public sealed interface Target {

        /** A player's attempt: the active provider first, then the load split's overflow. */
        record Flexible() implements Target {
        }

        /** Runs on one provider only; a {@code probe} also runs while the circuit is open. */
        record Pinned(String providerId, boolean probe) implements Target {
        }

        Target FLEXIBLE = new Flexible();
    }

    /**
     * The outcome of a job. {@code providerId} is the provider it ran on, the pinned provider when it was rejected
     * ("circuit open"), or null when it was cancelled or rejected before any provider was chosen.
     */
    public record Dispatched(String providerId, GenerationResult result, long latencyMs) {
    }

    private record Job(String id, Target target, GenerationRequest request, Consumer<String> onStart,
            boolean tracked, CompletableFuture<Dispatched> result) {
    }

    private record Selection(Job job, String providerId, boolean probe) {
    }

    /** Concurrency accounting of one provider; guarded by {@link #lock}. */
    private static final class Slot {
        final int max;
        int inFlight;
        int peak;

        Slot(int max) {
            this.max = max;
        }

        boolean free() {
            return inFlight < max;
        }
    }

    private final ProviderRegistry registry;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final LinkedHashMap<String, Job> pending = new LinkedHashMap<>();
    private final Map<String, Slot> slots = new LinkedHashMap<>();
    /**
     * Player attempts (jobs with a start callback) taken out of the FIFO for a provider, until the domain has
     * applied their result ({@link #forget(String)}); see {@link #started(String)}.
     */
    private final Set<String> startedJobs = ConcurrentHashMap.newKeySet();
    private final ExecutorService runners = Executors.newVirtualThreadPerTaskExecutor();
    private final Thread dispatcher;
    private volatile boolean running = true;

    public GenerationDispatcher(ProviderRegistry registry) {
        this.registry = registry;
        for (String id : registry.providerIds()) {
            slots.put(id, new Slot(Math.max(1, registry.generator(id).maxConcurrency())));
        }
        dispatcher = Thread.ofPlatform().daemon().name("generation-dispatcher").start(this::dispatchLoop);
    }

    public ProviderRegistry registry() {
        return registry;
    }

    /**
     * Queues a job at the end of the FIFO. {@code onStart} receives the provider when the job starts. The
     * future always completes, also when the job is cancelled or rejected.
     */
    public CompletableFuture<Dispatched> submit(String jobId, Target target, GenerationRequest request,
            Consumer<String> onStart) {
        CompletableFuture<Dispatched> result = new CompletableFuture<>();
        if (target instanceof Target.Pinned pin && !slots.containsKey(pin.providerId())) {
            result.complete(failed(null, "unknown provider"));
            return result;
        }
        Job job = new Job(jobId, target, request, onStart == null ? p -> {
        } : onStart, onStart != null, result);
        boolean accepted;
        lock.lock();
        try {
            accepted = running && !pending.containsKey(jobId) && !startedJobs.contains(jobId);
            if (accepted) {
                pending.put(jobId, job);
                changed.signalAll();
            }
        } finally {
            lock.unlock();
        }
        if (!accepted) {
            result.complete(failed(null, "rejected"));
        }
        return result;
    }

    /** Removes a job that has not started; its future completes as cancelled. A started job runs to its end. */
    public boolean cancel(String jobId) {
        Job job;
        lock.lock();
        try {
            job = pending.remove(jobId);
        } finally {
            lock.unlock();
        }
        if (job != null) {
            job.result().complete(failed(null, "cancelled"));
        }
        return job != null;
    }

    public void cancelAll() {
        List<Job> jobs;
        lock.lock();
        try {
            jobs = new ArrayList<>(pending.values());
            pending.clear();
        } finally {
            lock.unlock();
        }
        jobs.forEach(j -> j.result().complete(failed(null, "cancelled")));
    }

    /** Re-evaluates the FIFO (active provider or circuit changed). */
    public void wake() {
        lock.lock();
        try {
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Integer positionOf(String jobId) {
        lock.lock();
        try {
            int position = 1;
            for (String id : pending.keySet()) {
                if (id.equals(jobId)) {
                    return position;
                }
                position++;
            }
            return null;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean started(String jobId) {
        return startedJobs.contains(jobId);
    }

    /**
     * The domain applied this attempt's result: it no longer needs {@link #started(String)}. Called on the
     * engine thread right after {@code attemptFinished}, so a view never sees QUEUED without a position.
     */
    public void forget(String jobId) {
        startedJobs.remove(jobId);
    }

    /** Jobs waiting in the FIFO. */
    public int length() {
        lock.lock();
        try {
            return pending.size();
        } finally {
            lock.unlock();
        }
    }

    public int inFlight(String providerId) {
        lock.lock();
        try {
            Slot slot = slots.get(providerId);
            return slot == null ? 0 : slot.inFlight;
        } finally {
            lock.unlock();
        }
    }

    /** The highest number of calls a provider ever had in flight at once. */
    public int peakInFlight(String providerId) {
        lock.lock();
        try {
            Slot slot = slots.get(providerId);
            return slot == null ? 0 : slot.peak;
        } finally {
            lock.unlock();
        }
    }

    public int maxConcurrency(String providerId) {
        Slot slot = slots.get(providerId);
        return slot == null ? 0 : slot.max;
    }

    // ------------------------------------------------------------------ dispatching

    private void dispatchLoop() {
        while (running) {
            Selection selection = null;
            CircuitBreaker.Role role = null;
            List<Job> rejected = new ArrayList<>();
            lock.lock();
            try {
                while (running) {
                    selection = select(rejected);
                    if (selection != null || !rejected.isEmpty()) {
                        break;
                    }
                    if (pending.isEmpty()) {
                        changed.await();
                    } else {
                        changed.await(RECHECK_MILLIS, TimeUnit.MILLISECONDS);
                    }
                }
                for (Job j : rejected) {
                    pending.remove(j.id());
                }
                if (selection != null) {
                    pending.remove(selection.job().id());
                    if (selection.job().tracked()) {
                        startedJobs.add(selection.job().id());
                    }
                    Slot slot = slots.get(selection.providerId());
                    slot.inFlight++;
                    slot.peak = Math.max(slot.peak, slot.inFlight);
                    role = registry.onDispatch(selection.providerId(), selection.probe());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } finally {
                lock.unlock();
            }
            for (Job j : rejected) {
                j.result().complete(failed(((Target.Pinned) j.target()).providerId(), "circuit open"));
            }
            if (selection != null) {
                start(selection, role);
            }
        }
    }

    /** The first job in FIFO order that can start now and its provider; pinned jobs on an open circuit go to {@code rejected}. */
    private Selection select(List<Job> rejected) {
        List<String> candidates = null;
        boolean flexibleBlocked = false;
        for (Job job : pending.values()) {
            if (job.target() instanceof Target.Pinned pin) {
                String p = pin.providerId();
                if (!pin.probe() && registry.circuit(p) == CircuitState.OPEN) {
                    rejected.add(job);
                } else if (slots.get(p).free() && (pin.probe() || registry.allows(p))) {
                    return new Selection(job, p, pin.probe());
                }
                continue;
            }
            if (flexibleBlocked) {
                continue; // every attempt has the same candidates: none behind a blocked one can start either
            }
            if (candidates == null) {
                candidates = flexibleCandidates();
            }
            for (String p : candidates) {
                Slot slot = slots.get(p);
                if (slot != null && slot.free() && registry.allows(p)) {
                    return new Selection(job, p, false);
                }
            }
            flexibleBlocked = true;
        }
        return null;
    }

    /**
     * The active provider, then (if it is listed in IMAGE_LOAD_SPLIT) the other listed providers. When none
     * of those is closed, every chain provider is a candidate too: the domain switches the active provider
     * right after a circuit opens, and with all circuits unhealthy a half-open trial may come from any of them.
     */
    private List<String> flexibleCandidates() {
        List<String> order = new ArrayList<>();
        String active = registry.active();
        if (active != null) {
            order.add(active);
            if (registry.loadSplit().contains(active)) {
                for (String p : registry.loadSplit()) {
                    if (!order.contains(p)) {
                        order.add(p);
                    }
                }
            }
        }
        boolean anyClosed = false;
        for (String p : order) {
            if (registry.circuit(p) == CircuitState.CLOSED) {
                anyClosed = true;
                break;
            }
        }
        if (!anyClosed) {
            for (String p : registry.chain()) {
                if (!order.contains(p)) {
                    order.add(p);
                }
            }
        }
        return order;
    }

    private void start(Selection selection, CircuitBreaker.Role role) {
        try {
            runners.execute(() -> run(selection.job(), selection.providerId(), role));
        } catch (RejectedExecutionException e) {
            release(selection.providerId());
            selection.job().result().complete(failed(null, "rejected"));
        }
    }

    /** Runs one job; the slot is released and the future completed whatever happens, even on an Error. */
    private void run(Job job, String providerId, CircuitBreaker.Role role) {
        GenerationResult result = new GenerationResult.Failure(FailureKind.PROVIDER_ERROR, "aborted");
        long latencyMs = 0;
        try {
            try {
                job.onStart().accept(providerId);
            } catch (RuntimeException e) {
                log.warn("Generation start callback failed: {}", e.getClass().getSimpleName());
            }
            long started = System.nanoTime();
            try {
                result = registry.generator(providerId).generate(job.request());
                if (result == null) {
                    result = new GenerationResult.Failure(FailureKind.INVALID_RESPONSE, "no result");
                }
            } catch (RuntimeException e) {
                log.warn("Image provider {} threw {}", providerId, e.getClass().getSimpleName());
                result = new GenerationResult.Failure(FailureKind.PROVIDER_ERROR, e.getClass().getSimpleName());
            }
            latencyMs = (System.nanoTime() - started) / 1_000_000;
            try {
                if (running) {
                    registry.record(providerId, result, latencyMs, role);
                }
            } catch (RuntimeException e) {
                log.error("Recording the result of {} failed", providerId, e);
            }
        } finally {
            release(providerId);
            job.result().complete(new Dispatched(providerId, result, latencyMs));
        }
    }

    private void release(String providerId) {
        lock.lock();
        try {
            slots.get(providerId).inFlight--;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private static Dispatched failed(String providerId, String reason) {
        return new Dispatched(providerId, new GenerationResult.Failure(FailureKind.PROVIDER_ERROR, reason), 0);
    }

    @Override
    public void close() {
        running = false;
        wake();
        dispatcher.interrupt();
        cancelAll();
        runners.shutdownNow();
    }
}
