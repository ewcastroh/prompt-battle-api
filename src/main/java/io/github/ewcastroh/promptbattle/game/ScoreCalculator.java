package io.github.ewcastroh.promptbattle.game;

import java.util.List;

/** Similarity scoring (SPEC §4.1). */
public final class ScoreCalculator {

    /** Anchors more similar than this to the target are discarded from the floor. */
    public static final double ANCHOR_EXCLUSION = 0.9;

    private ScoreCalculator() {
    }

    /** Cosine of two vectors after L2 normalization. */
    public static double cosine(double[] a, double[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("Dimension mismatch " + a.length + " vs " + b.length);
        }
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    public static boolean excluded(double anchorCos) {
        return anchorCos > ANCHOR_EXCLUSION;
    }

    /** Mean cosine of the non-excluded anchors; 0 when every anchor is excluded or there are none. */
    public static double floor(List<Double> anchorCos) {
        double sum = 0;
        int n = 0;
        for (double c : anchorCos) {
            if (!excluded(c)) {
                sum += c;
                n++;
            }
        }
        return n == 0 ? 0 : sum / n;
    }

    /** {@code round1(100 × clamp((cos − floor) / (1 − floor), 0, 1))}. */
    public static double score(double cos, double floor) {
        double span = 1 - floor;
        double normalized = span <= 1e-9 ? (cos >= floor ? 1 : 0) : (cos - floor) / span;
        double clamped = Math.max(0, Math.min(1, normalized));
        return round1(100 * clamped);
    }

    public static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
