package io.github.ewcastroh.promptbattle.generation;

import io.github.ewcastroh.promptbattle.game.FailureKind;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.SplittableRandom;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * Offline image provider (SPEC §5.2 {@code fake}): a deterministic 1024² JPEG from {@code hash(prompt, seed)}
 * that carries the prompt in a COM segment, so {@code FakeEmbeddingProvider} can score it.
 * <p>Markers for tests and load runs: {@code [fail]} → PROVIDER_ERROR, {@code [unsafe]} → SAFETY_BLOCKED,
 * {@code [delay=ms]} overrides the latency. A random {@code failureRate} gives PROVIDER_ERROR.
 */
public final class FakeImageGenerator implements ImageGenerator {

    public static final String ID = "fake";
    public static final String COMMENT_PREFIX = "pb-fake:";
    private static final Pattern DELAY = Pattern.compile("\\[delay=(\\d{1,6})]");

    private final String model;
    private final long latencyMs;
    private final double failureRate;
    private final int maxConcurrency;

    public FakeImageGenerator(String model, long latencyMs, double failureRate, int maxConcurrency) {
        this.model = model;
        this.latencyMs = latencyMs;
        this.failureRate = failureRate;
        this.maxConcurrency = maxConcurrency;
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
    public int maxConcurrency() {
        return maxConcurrency;
    }

    @Override
    public GenerationResult generate(GenerationRequest request) {
        long started = System.nanoTime();
        String prompt = request.prompt();
        Matcher delay = DELAY.matcher(prompt);
        sleep(delay.find() ? Long.parseLong(delay.group(1)) : latencyMs);
        if (prompt.contains("[fail]") || (failureRate > 0 && ThreadLocalRandom.current().nextDouble() < failureRate)) {
            return new GenerationResult.Failure(FailureKind.PROVIDER_ERROR, "fake failure");
        }
        if (prompt.contains("[unsafe]")) {
            return new GenerationResult.Failure(FailureKind.SAFETY_BLOCKED, "fake safety block");
        }
        byte[] jpeg = JpegComment.write(render(prompt, request.seed(), request.width(), request.height()),
                COMMENT_PREFIX + prompt);
        return new GenerationResult.Success(jpeg, "image/jpeg", model, (System.nanoTime() - started) / 1_000_000);
    }

    private static void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** A deterministic JPEG for {@code (prompt, seed)}; also the probe's local test image for embeddings. */
    public static byte[] render(String prompt, long seed, int width, int height) {
        SplittableRandom random = new SplittableRandom(seedOf(prompt, seed));
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(new Color(random.nextInt(0x1000000)));
            g.fillRect(0, 0, width, height);
            for (int i = 0; i < 8; i++) {
                g.setColor(new Color(random.nextInt(0x1000000)));
                int w = 64 + random.nextInt(width / 2);
                int h = 64 + random.nextInt(height / 2);
                int x = random.nextInt(width - w);
                int y = random.nextInt(height - h);
                if (random.nextBoolean()) {
                    g.fillOval(x, y, w, h);
                } else {
                    g.fillRect(x, y, w, h);
                }
            }
        } finally {
            g.dispose();
        }
        return encodeJpeg(image, 0.85f);
    }

    static long seedOf(String prompt, long seed) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest((seed + "|" + prompt).getBytes(StandardCharsets.UTF_8));
            long v = 0;
            for (int i = 0; i < 8; i++) {
                v = (v << 8) | (d[i] & 0xFF);
            }
            return v;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] encodeJpeg(BufferedImage image, float quality) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            writer.setOutput(out);
            writer.write(null, new IIOImage(image, null, null), param);
        } catch (IOException e) {
            throw new IllegalStateException("JPEG encoding failed", e);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }
}
