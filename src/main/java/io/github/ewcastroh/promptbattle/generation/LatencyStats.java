package io.github.ewcastroh.promptbattle.generation;

import java.util.Arrays;

/** p50 and p95 latency over the last {@value #CAPACITY} successful calls of one provider. Thread-safe. */
public final class LatencyStats {

    public static final int CAPACITY = 200;

    private final long[] ring = new long[CAPACITY];
    private int size;
    private int next;

    public synchronized void clear() {
        size = 0;
        next = 0;
    }

    public synchronized void record(long millis) {
        ring[next] = millis;
        next = (next + 1) % CAPACITY;
        size = Math.min(size + 1, CAPACITY);
    }

    /** Nearest-rank percentile ({@code 0 < p ≤ 100}), or null without samples. */
    public synchronized Long percentile(double p) {
        if (size == 0) {
            return null;
        }
        long[] sorted = Arrays.copyOf(ring, size);
        Arrays.sort(sorted);
        int rank = (int) Math.ceil(p / 100.0 * size);
        return sorted[Math.max(0, Math.min(size, rank) - 1)];
    }

    public Long p50() {
        return percentile(50);
    }

    public Long p95() {
        return percentile(95);
    }
}
