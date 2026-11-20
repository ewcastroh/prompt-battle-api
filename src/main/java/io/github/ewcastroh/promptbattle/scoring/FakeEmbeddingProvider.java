package io.github.ewcastroh.promptbattle.scoring;

import io.github.ewcastroh.promptbattle.generation.FakeImageGenerator;
import io.github.ewcastroh.promptbattle.generation.JpegComment;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic offline embeddings (PLAN A1), driven by the prompt the fake generator writes in the JPEG:
 * <ul>
 * <li>No fake prompt (e.g. an uploaded target) or a prompt containing {@code [target]} → the fixed unit
 * vector T = e0.</li>
 * <li>A prompt containing {@code [sim=0.83]} → a vector with exactly that cosine to T.</li>
 * <li>Any other prompt → a bag-of-words vector orthogonal to T, so prompts sharing words with a fake
 * target's prompt score higher; anchors get cosine 0 to T and the floor is 0.</li>
 * </ul>
 */
public final class FakeEmbeddingProvider implements EmbeddingProvider {

    public static final String ID = "fake";
    private static final Pattern SIM = Pattern.compile("\\[sim=(-?[0-9]*\\.?[0-9]+)]");
    private static final Pattern MARKER = Pattern.compile("\\[[^]]*]");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");

    private final String model;
    private final int dimensions;

    public FakeEmbeddingProvider(String model, int dimensions) {
        if (dimensions < 2) {
            throw new IllegalArgumentException("dimensions must be ≥ 2");
        }
        this.model = model;
        this.dimensions = dimensions;
    }

    @Override
    public String providerId() {
        return ID;
    }

    @Override
    public String modelId() {
        return model;
    }

    @Override
    public double[] embed(byte[] image, String mimeType) {
        String comment = JpegComment.read(image);
        String prompt = comment != null && comment.startsWith(FakeImageGenerator.COMMENT_PREFIX)
                ? comment.substring(FakeImageGenerator.COMMENT_PREFIX.length())
                : null;
        double[] v = new double[dimensions];
        if (prompt == null || prompt.contains("[target]")) {
            v[0] = 1;
            return v;
        }
        Matcher sim = SIM.matcher(prompt);
        if (sim.find()) {
            double x = Math.max(-1, Math.min(1, Double.parseDouble(sim.group(1))));
            double[] u = orthogonalNoise(prompt);
            double y = Math.sqrt(1 - x * x);
            for (int i = 1; i < dimensions; i++) {
                v[i] = y * u[i];
            }
            v[0] = x;
            return v;
        }
        String text = MARKER.matcher(prompt).replaceAll(" ");
        text = MARKS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("").toLowerCase(Locale.ROOT);
        boolean any = false;
        for (String word : text.split("[^\\p{L}\\p{Nd}]+")) {
            if (word.length() < 2) {
                continue;
            }
            any = true;
            SplittableRandom random = new SplittableRandom(hash(word));
            for (int i = 1; i < dimensions; i++) {
                v[i] += random.nextGaussian();
            }
        }
        return any ? normalize(v) : orthogonalNoise(prompt);
    }

    /** A unit vector with v[0] = 0. */
    private double[] orthogonalNoise(String seedText) {
        double[] u = new double[dimensions];
        SplittableRandom random = new SplittableRandom(hash("noise|" + seedText));
        for (int i = 1; i < dimensions; i++) {
            u[i] = random.nextGaussian();
        }
        return normalize(u);
    }

    private static double[] normalize(double[] v) {
        double norm = 0;
        for (double x : v) {
            norm += x * x;
        }
        norm = Math.sqrt(norm);
        for (int i = 0; i < v.length; i++) {
            v[i] /= norm;
        }
        return v;
    }

    private static long hash(String text) {
        long h = 1125899906842597L;
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            h = 31 * h + b;
        }
        return h;
    }
}
