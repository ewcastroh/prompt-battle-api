package io.github.ewcastroh.promptbattle.scoring;

/**
 * SPEC §4.2: the probe embeds the same image twice; if the vectors differ by more than {@value #THRESHOLD}
 * (max absolute difference after L2 normalization), scoring averages two embedding calls per image.
 */
public final class DeterminismCheck {

    public static final double THRESHOLD = 1e-4;

    private DeterminismCheck() {
    }

    /** Max absolute component difference of the two normalized vectors; infinite when dimensions differ. */
    public static double maxDiff(double[] a, double[] b) {
        if (a.length != b.length) {
            return Double.POSITIVE_INFINITY;
        }
        double[] x = EmbeddingService.normalize(a);
        double[] y = EmbeddingService.normalize(b);
        double max = 0;
        for (int i = 0; i < x.length; i++) {
            max = Math.max(max, Math.abs(x[i] - y[i]));
        }
        return max;
    }

    public static boolean needsAveraging(double maxDiff) {
        return maxDiff > THRESHOLD;
    }
}
