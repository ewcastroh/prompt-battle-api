package io.github.ewcastroh.promptbattle.scoring;

import io.github.ewcastroh.promptbattle.generation.FakeImageGenerator;
import io.github.ewcastroh.promptbattle.generation.JpegComment;
import io.github.ewcastroh.promptbattle.persistence.ImageStore;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An in-process embedding provider for tests. Images carry their name in a JPEG comment ({@link #image});
 * vectors depend on the provider id and the name, so vectors of two providers never match. Records calls per
 * name and can fail, delay, block or refuse best-effort calls on demand.
 */
final class RecordingEmbeddingProvider implements EmbeddingProvider {

    static final int DIMENSIONS = 16;
    private static final String PREFIX = "name:";

    final String id;
    final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    final Set<String> failing = ConcurrentHashMap.newKeySet();
    /** Name → how many of its next calls fail (a transient error). */
    final Map<String, Integer> failNext = new ConcurrentHashMap<>();
    /** Name → extra delay of each of its calls. */
    final Map<String, Long> slow = new ConcurrentHashMap<>();
    volatile EmbeddingException.Kind failKind = EmbeddingException.Kind.PROVIDER_ERROR;
    volatile boolean capacity = true;
    volatile boolean skipWithoutWaiting;
    /** Every call waits for this latch when set. */
    volatile CountDownLatch gate;
    volatile long delayMillis;
    /** Added to component 0 times the call number of that image, so repeated calls differ. */
    volatile double jitter;

    RecordingEmbeddingProvider(String id) {
        this.id = id;
    }

    /** Stores a named test image and returns its image id. */
    static String image(ImageStore images, String name) {
        byte[] jpeg = FakeImageGenerator.render(name, 1, 256, 256);
        return images.store(JpegComment.write(jpeg, PREFIX + name));
    }

    static String nameOf(byte[] jpeg) {
        String comment = JpegComment.read(jpeg);
        return comment != null && comment.startsWith(PREFIX) ? comment.substring(PREFIX.length()) : "?";
    }

    int calls(String name) {
        AtomicInteger n = calls.get(name);
        return n == null ? 0 : n.get();
    }

    /** The provider's raw vector for an image name (call {@code n}, 1-based). */
    double[] vectorFor(String name, int n) {
        SplittableRandom random = new SplittableRandom(hash(id + "|" + name));
        double[] v = new double[DIMENSIONS];
        for (int i = 0; i < v.length; i++) {
            v[i] = random.nextGaussian();
        }
        v[0] += jitter * n;
        return v;
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
    public double[] embed(byte[] image, String mimeType) {
        String name = nameOf(image);
        int n = calls.computeIfAbsent(name, k -> new AtomicInteger()).incrementAndGet();
        CountDownLatch g = gate;
        try {
            if (g != null && !g.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("gate never opened");
            }
            long delay = delayMillis + slow.getOrDefault(name, 0L);
            if (delay > 0) {
                Thread.sleep(delay);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EmbeddingException(EmbeddingException.Kind.TIMEOUT, "Interrupted");
        }
        boolean transientFailure = false;
        synchronized (failNext) {
            Integer left = failNext.get(name);
            if (left != null) {
                transientFailure = true;
                if (left <= 1) {
                    failNext.remove(name);
                } else {
                    failNext.put(name, left - 1);
                }
            }
        }
        if (transientFailure) {
            throw new EmbeddingException(failKind, id + " fails this time for " + name);
        }
        if (failing.contains(name)) {
            throw new EmbeddingException(failKind, id + " fails for " + name);
        }
        return vectorFor(name, n);
    }

    @Override
    public double[] embedWithoutWaiting(byte[] image, String mimeType) {
        if (skipWithoutWaiting) {
            throw new EmbeddingException.Skipped("no capacity");
        }
        return embed(image, mimeType);
    }

    @Override
    public boolean hasCapacityNow() {
        return capacity;
    }

    private static long hash(String text) {
        long h = 1125899906842597L;
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            h = 31 * h + b;
        }
        return h;
    }
}
