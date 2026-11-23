package io.github.ewcastroh.promptbattle.scoring;

import io.github.ewcastroh.promptbattle.persistence.ImageStore;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Embeds stored images (SPEC §4.3): cached by {@code (imageId, provider)}, at most {@code maxConcurrency}
 * calls per provider at once, and concurrent requests for the same image share one call. With averaging on
 * (SPEC §4.2) a scoring vector is the normalized mean of two calls. Vectors are L2-normalized.
 */
public final class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    /** {@code call} is 1 or 2: averaging needs a second, independent call for the same image. */
    private record Key(String imageId, String provider, int call) {
    }

    /** Admin view of one provider's recent calls. */
    public record Health(Instant lastSuccessAt, Instant lastFailureAt, EmbeddingException.Kind lastFailureKind) {
    }

    private final Map<String, EmbeddingProvider> providers = new LinkedHashMap<>();
    private final Map<String, Semaphore> permits = new LinkedHashMap<>();
    private final ImageStore images;
    private final Clock clock;
    private final Map<Key, Shared> cache = new ConcurrentHashMap<>();

    /** A cached call and whether its owner waits (scoring) or is a best-effort prefetch (no retries). */
    private record Shared(CompletableFuture<double[]> future, boolean waiting) {
    }
    private final Set<String> averaging = ConcurrentHashMap.newKeySet();
    private final Map<String, Health> health = new ConcurrentHashMap<>();

    public EmbeddingService(List<EmbeddingProvider> providers, ImageStore images, int maxConcurrency, Clock clock) {
        for (EmbeddingProvider p : providers) {
            this.providers.put(p.providerId(), p);
            this.permits.put(p.providerId(), new Semaphore(Math.max(1, maxConcurrency), true));
        }
        this.images = images;
        this.clock = clock;
    }

    public boolean has(String providerId) {
        return providerId != null && providers.containsKey(providerId);
    }

    public List<String> providerIds() {
        return List.copyOf(providers.keySet());
    }

    public EmbeddingProvider provider(String providerId) {
        return providers.get(providerId);
    }

    public boolean averaging(String providerId) {
        return averaging.contains(providerId);
    }

    /** Mirrors the domain's determinism decision (effect {@code EmbeddingAveraging}). */
    public void setAveraging(String providerId, boolean on) {
        if (on) {
            averaging.add(providerId);
        } else {
            averaging.remove(providerId);
        }
    }

    public Health health(String providerId) {
        return health.get(providerId);
    }

    /** The scoring vector of a stored image with the provider's current averaging mode. */
    public double[] embed(String imageId, String providerId) {
        return embed(imageId, providerId, averaging(providerId));
    }

    /** The scoring vector of a stored image: one call, or the normalized mean of two calls. */
    public double[] embed(String imageId, String providerId, boolean average) {
        return embed(imageId, providerId, average, true);
    }

    private double[] embed(String imageId, String providerId, boolean average, boolean wait) {
        double[] first = call(imageId, providerId, 1, wait);
        if (!average) {
            return first;
        }
        double[] second = call(imageId, providerId, 2, wait);
        if (second.length != first.length) {
            throw new EmbeddingException(EmbeddingException.Kind.INVALID_RESPONSE, "Dimension changed between calls");
        }
        double[] mean = new double[first.length];
        for (int i = 0; i < mean.length; i++) {
            mean[i] = (first[i] + second[i]) / 2;
        }
        return normalize(mean);
    }

    /**
     * Best effort and never waits: not for a call slot (scoring has priority), not for the rate limit. Skipped
     * when there is no capacity right now; scoring embeds whatever is missing. Failures are only logged.
     */
    public void prefetch(String imageId, String providerId) {
        EmbeddingProvider provider = providers.get(providerId);
        if (provider == null || !provider.hasCapacityNow()) {
            return;
        }
        try {
            embed(imageId, providerId, averaging(providerId), false);
        } catch (EmbeddingException.Skipped e) {
            log.debug("Prefetch embedding with {} skipped: no capacity", providerId);
        } catch (RuntimeException e) {
            log.debug("Prefetch embedding with {} failed", providerId);
        }
    }

    /** An uncached call on raw bytes (the probe's determinism check). */
    public double[] embedBytes(String providerId, byte[] image, String mimeType) {
        EmbeddingProvider provider = require(providerId);
        return normalize(limited(providerId, () -> provider.embed(image, mimeType), true));
    }

    public void clear() {
        cache.clear();
    }

    private EmbeddingProvider require(String providerId) {
        EmbeddingProvider provider = providers.get(providerId);
        if (provider == null) {
            throw new EmbeddingException("Unknown embedding provider " + providerId);
        }
        return provider;
    }

    /**
     * The {@code n}-th vector of an image, cached; callers of the same key share one call. The owner's work is
     * never interrupted by other callers. A shared call that a prefetch skipped or failed is redone by a waiting
     * caller: a prefetch makes one attempt without retries, scoring retries and must not inherit its failure.
     */
    private double[] call(String imageId, String providerId, int n, boolean wait) {
        EmbeddingProvider provider = require(providerId);
        Key key = new Key(imageId, providerId, n);
        Shared shared = new Shared(new CompletableFuture<>(), wait);
        CompletableFuture<double[]> mine = shared.future();
        Shared existing = cache.putIfAbsent(key, shared);
        if (existing != null) {
            try {
                return join(existing.future());
            } catch (EmbeddingException.Skipped skipped) {
                if (!wait) {
                    throw skipped;
                }
                return call(imageId, providerId, n, true); // the owner was a prefetch that gave up
            } catch (EmbeddingException failed) {
                if (wait && !existing.waiting()) {
                    return call(imageId, providerId, n, true); // the owner was a prefetch: retry it properly
                }
                throw failed;
            }
        }
        try {
            byte[] bytes = images.read(imageId)
                    .orElseThrow(() -> new EmbeddingException("Missing image " + imageId));
            double[] vector = normalize(limited(providerId, wait ? () -> provider.embed(bytes, "image/jpeg")
                    : () -> provider.embedWithoutWaiting(bytes, "image/jpeg"), wait));
            mine.complete(vector);
            return vector;
        } catch (RuntimeException e) {
            cache.remove(key, shared);
            mine.completeExceptionally(e);
            throw e;
        }
    }

    private interface Call {
        double[] run();
    }

    /** At most {@code maxConcurrency} calls per provider; without {@code wait}, gives up instead of queueing. */
    private double[] limited(String providerId, Call call, boolean wait) {
        Semaphore semaphore = permits.get(providerId);
        if (!wait) {
            if (!semaphore.tryAcquire()) {
                throw new EmbeddingException.Skipped("No free embedding call slot");
            }
        } else {
            try {
                semaphore.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new EmbeddingException(EmbeddingException.Kind.TIMEOUT, "Interrupted");
            }
        }
        try {
            double[] vector;
            try {
                vector = call.run();
            } catch (EmbeddingException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new EmbeddingException("Embedding provider failed: " + e.getClass().getSimpleName(), e);
            }
            if (vector == null || vector.length == 0) {
                throw new EmbeddingException(EmbeddingException.Kind.INVALID_RESPONSE, "Empty embedding");
            }
            Instant at = clock.instant();
            health.compute(providerId, (k, prev) -> prev == null ? new Health(at, null, null)
                    : new Health(later(prev.lastSuccessAt(), at), prev.lastFailureAt(), prev.lastFailureKind()));
            return vector;
        } catch (EmbeddingException.Skipped e) {
            throw e; // no call was made: says nothing about the provider's health
        } catch (EmbeddingException e) {
            Instant at = clock.instant();
            health.compute(providerId, (k, prev) -> prev == null ? new Health(null, at, e.kind())
                    : prev.lastFailureAt() != null && prev.lastFailureAt().isAfter(at) ? prev
                    : new Health(prev.lastSuccessAt(), at, e.kind()));
            throw e;
        } finally {
            semaphore.release();
        }
    }

    private static Instant later(Instant a, Instant b) {
        return a == null || b.isAfter(a) ? b : a;
    }

    private static double[] join(CompletableFuture<double[]> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof EmbeddingException ee) {
                throw ee;
            }
            throw new EmbeddingException("Embedding failed", e.getCause());
        }
    }

    /** L2 normalization (a zero vector stays zero). */
    public static double[] normalize(double[] v) {
        double norm = 0;
        for (double x : v) {
            norm += x * x;
        }
        if (norm == 0) {
            return v.clone();
        }
        double inv = 1 / Math.sqrt(norm);
        double[] out = new double[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = v[i] * inv;
        }
        return out;
    }
}
