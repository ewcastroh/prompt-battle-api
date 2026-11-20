package io.github.ewcastroh.promptbattle.generation;

import io.github.ewcastroh.promptbattle.game.CircuitState;
import io.github.ewcastroh.promptbattle.game.FailureKind;
import io.github.ewcastroh.promptbattle.game.ProviderHealth;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The configured image providers (those with an adapter, i.e. a key) with their circuit breaker, latency and
 * counters. The active provider is decided by the domain ({@code Game}) and mirrored here for the
 * dispatcher. Circuit transitions are reported to a {@link Listener} after the breaker's lock is released.
 */
public final class ProviderRegistry implements ProviderHealth {

    private static final Logger log = LoggerFactory.getLogger(ProviderRegistry.class);

    /** Receives circuit transitions; must not block (it is called on generation threads). */
    public interface Listener {
        void circuitOpened(String providerId);

        void circuitClosed(String providerId);

        Listener NONE = new Listener() {
            @Override
            public void circuitOpened(String providerId) {
            }

            @Override
            public void circuitClosed(String providerId) {
            }
        };
    }

    /** Admin view of one provider (API.md {@code GET /api/admin/providers}). */
    public record Stats(String id, String model, CircuitState circuit, int maxConcurrency, Long p50Ms, Long p95Ms,
            long successCount, long failureCount, FailureKind lastErrorKind, Instant lastErrorAt) {
    }

    private record LastError(FailureKind kind, Instant at) {
    }

    private static final class Entry {
        final ImageGenerator generator;
        final CircuitBreaker breaker;
        final LatencyStats latency = new LatencyStats();
        final AtomicLong successes = new AtomicLong();
        final AtomicLong failures = new AtomicLong();
        volatile LastError lastError;

        Entry(ImageGenerator generator, Clock clock) {
            this.generator = generator;
            this.breaker = new CircuitBreaker(clock);
        }
    }

    private final Map<String, Entry> providers = new LinkedHashMap<>();
    private final List<String> chain;
    private final List<String> loadSplit;
    private final Clock clock;
    private volatile String active;
    private volatile Listener listener = Listener.NONE;

    /**
     * @param chain     IMAGE_PROVIDER_CHAIN; providers without an adapter are skipped
     * @param loadSplit IMAGE_LOAD_SPLIT; empty = off. Only providers with an adapter are kept.
     */
    public ProviderRegistry(List<ImageGenerator> generators, List<String> chain, List<String> loadSplit, Clock clock) {
        this.clock = clock;
        for (ImageGenerator g : generators) {
            providers.put(g.providerId(), new Entry(g, clock));
        }
        this.chain = known(chain);
        this.loadSplit = known(loadSplit);
        this.active = this.chain.isEmpty() ? null : this.chain.getFirst();
        List<String> skipped = new ArrayList<>(loadSplit.stream().map(String::strip).filter(s -> !s.isEmpty()).toList());
        skipped.removeAll(this.loadSplit);
        if (!skipped.isEmpty()) {
            log.warn("IMAGE_LOAD_SPLIT providers without an adapter (no key) are not used: {}", skipped);
        }
    }

    private List<String> known(List<String> ids) {
        List<String> result = new ArrayList<>();
        for (String id : ids) {
            String trimmed = id.strip();
            if (!trimmed.isEmpty() && providers.containsKey(trimmed) && !result.contains(trimmed)) {
                result.add(trimmed);
            }
        }
        return List.copyOf(result);
    }

    public void setListener(Listener listener) {
        this.listener = listener == null ? Listener.NONE : listener;
    }

    /** Configured providers of IMAGE_PROVIDER_CHAIN, in failover order. */
    public List<String> chain() {
        return chain;
    }

    /** Configured providers of IMAGE_LOAD_SPLIT, in overflow order (empty = split off). */
    public List<String> loadSplit() {
        return loadSplit;
    }

    /** Every provider with an adapter, chain providers first. */
    public List<String> providerIds() {
        List<String> ids = new ArrayList<>(chain);
        for (String id : providers.keySet()) {
            if (!ids.contains(id)) {
                ids.add(id);
            }
        }
        return ids;
    }

    /** Providers of IMAGE_PROVIDER_CHAIN and IMAGE_LOAD_SPLIT that have an adapter (what admins see and probe). */
    public List<String> listed() {
        List<String> ids = new ArrayList<>(chain);
        for (String id : loadSplit) {
            if (!ids.contains(id)) {
                ids.add(id);
            }
        }
        return ids;
    }

    public boolean has(String providerId) {
        return providerId != null && providers.containsKey(providerId);
    }

    public ImageGenerator generator(String providerId) {
        Entry e = providers.get(providerId);
        return e == null ? null : e.generator;
    }

    public String active() {
        return active;
    }

    /** Mirrors the domain's active provider (effect {@code ActiveImageProvider}). */
    public void setActive(String providerId) {
        if (has(providerId)) {
            active = providerId;
        }
    }

    @Override
    public CircuitState circuit(String providerId) {
        Entry e = providers.get(providerId);
        return e == null ? CircuitState.OPEN : e.breaker.state();
    }

    /** Whether a normal call may be dispatched to the provider now (closed, or a free half-open trial). */
    public boolean allows(String providerId) {
        Entry e = providers.get(providerId);
        return e != null && e.breaker.allows();
    }

    CircuitBreaker.Role onDispatch(String providerId, boolean probe) {
        return providers.get(providerId).breaker.onDispatch(probe);
    }

    /** Counts a finished call and feeds the breaker; SAFETY_BLOCKED is never a failure. */
    public void record(String providerId, GenerationResult result, long latencyMs, CircuitBreaker.Role role) {
        Entry e = providers.get(providerId);
        if (e == null) {
            return;
        }
        boolean failure = false;
        switch (result) {
            case GenerationResult.Success ignored -> {
                e.successes.incrementAndGet();
                e.latency.record(latencyMs);
            }
            case GenerationResult.Failure f -> {
                if (f.kind() != FailureKind.SAFETY_BLOCKED) {
                    failure = true;
                    e.failures.incrementAndGet();
                    e.lastError = new LastError(f.kind(), clock.instant());
                }
            }
        }
        notify(providerId, e.breaker.record(failure, role));
    }

    /** Closes a provider's circuit (an admin forced it). */
    public void reset(String providerId) {
        Entry e = providers.get(providerId);
        if (e != null) {
            notify(providerId, e.breaker.reset());
        }
    }

    /**
     * Event reset: every circuit closed and every counter cleared, so a fresh event starts with fresh provider
     * health. The active provider (domain) is kept.
     */
    public void resetAll() {
        for (Map.Entry<String, Entry> e : providers.entrySet()) {
            Entry entry = e.getValue();
            entry.successes.set(0);
            entry.failures.set(0);
            entry.lastError = null;
            entry.latency.clear();
            notify(e.getKey(), entry.breaker.reset());
        }
    }

    private void notify(String providerId, CircuitBreaker.Transition transition) {
        switch (transition) {
            case OPENED -> {
                log.warn("Circuit of image provider {} opened", providerId);
                listener.circuitOpened(providerId);
            }
            case CLOSED -> {
                log.info("Circuit of image provider {} closed", providerId);
                listener.circuitClosed(providerId);
            }
            case NONE -> {
            }
        }
    }

    public Stats stats(String providerId) {
        Entry e = providers.get(providerId);
        if (e == null) {
            return null;
        }
        LastError last = e.lastError;
        return new Stats(providerId, e.generator.modelId(), e.breaker.state(), e.generator.maxConcurrency(),
                e.latency.p50(), e.latency.p95(), e.successes.get(), e.failures.get(),
                last == null ? null : last.kind(), last == null ? null : last.at());
    }
}
