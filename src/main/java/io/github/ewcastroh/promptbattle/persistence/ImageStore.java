package io.github.ewcastroh.promptbattle.persistence;

import io.github.ewcastroh.promptbattle.generation.FakeImageGenerator;
import io.github.ewcastroh.promptbattle.generation.JpegComment;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.imageio.ImageIO;

/**
 * Images under {@code DATA_DIR/images}: {@code {uuid}.jpg} (1024² JPEG q85, center-cropped) and
 * {@code {uuid}_thumb.jpg} (384 px). A JPEG comment in the source is kept (fake providers use it).
 */
public final class ImageStore {

    public static final int SIZE = 1024;
    public static final int THUMB = 384;
    private static final float QUALITY = 0.85f;
    private static final Pattern ID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final Path dir;

    public ImageStore(Path dataDir) {
        this.dir = dataDir.resolve("images");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create " + dir, e);
        }
    }

    /** Thrown when the source bytes are not a decodable image. */
    public static final class InvalidImageException extends RuntimeException {
        public InvalidImageException(String message) {
            super(message);
        }
    }

    /** Normalizes and stores an image; returns its new id. */
    public String store(byte[] source) {
        BufferedImage decoded;
        try {
            decoded = ImageIO.read(new ByteArrayInputStream(source));
        } catch (IOException e) {
            throw new InvalidImageException("Unreadable image");
        }
        if (decoded == null) {
            throw new InvalidImageException("Unsupported image format");
        }
        BufferedImage full = squareRgb(decoded, SIZE);
        BufferedImage thumb = scale(full, THUMB);
        byte[] fullJpeg = FakeImageGenerator.encodeJpeg(full, QUALITY);
        byte[] thumbJpeg = FakeImageGenerator.encodeJpeg(thumb, QUALITY);
        String comment = JpegComment.read(source);
        if (comment != null) {
            fullJpeg = JpegComment.write(fullJpeg, comment);
        }
        String id = UUID.randomUUID().toString();
        write(path(id, false), fullJpeg);
        write(path(id, true), thumbJpeg);
        return id;
    }

    public Optional<byte[]> read(String id) {
        Optional<Path> p = file(id, false);
        if (p.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(p.get()));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** The file of an image, if the id is well-formed and the file exists. */
    public Optional<Path> file(String id, boolean thumb) {
        if (id == null || !ID.matcher(id).matches()) {
            return Optional.empty();
        }
        Path p = path(id, thumb);
        return Files.isRegularFile(p) ? Optional.of(p) : Optional.empty();
    }

    public void deleteAll() {
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot delete images", e);
        }
    }

    private Path path(String id, boolean thumb) {
        return dir.resolve(id + (thumb ? "_thumb.jpg" : ".jpg"));
    }

    private static void write(Path target, byte[] bytes) {
        try {
            Path tmp = Files.createTempFile(target.getParent(), ".img", ".tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + target, e);
        }
    }

    /** Center-crops to a square and scales to {@code size}, on an opaque white background. */
    static BufferedImage squareRgb(BufferedImage src, int size) {
        int side = Math.min(src.getWidth(), src.getHeight());
        int x = (src.getWidth() - side) / 2;
        int y = (src.getHeight() - side) / 2;
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, size, size);
            g.drawImage(src, 0, 0, size, size, x, y, x + side, y + side, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    static BufferedImage scale(BufferedImage src, int size) {
        return squareRgb(src, size);
    }
}
