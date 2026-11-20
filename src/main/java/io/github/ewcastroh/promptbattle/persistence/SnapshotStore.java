package io.github.ewcastroh.promptbattle.persistence;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/** {@code DATA_DIR/snapshot.json}, written atomically (temp file + ATOMIC_MOVE). */
public final class SnapshotStore {

    public static final String FILE = "snapshot.json";
    private final Path file;

    public SnapshotStore(Path dataDir) {
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create " + dataDir, e);
        }
        this.file = dataDir.resolve(FILE);
    }

    public Path file() {
        return file;
    }

    public Optional<byte[]> load() {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    public void write(byte[] json) {
        try {
            Path tmp = Files.createTempFile(file.getParent(), ".snapshot", ".tmp");
            Files.write(tmp, json);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + file, e);
        }
    }

    public void delete() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot delete " + file, e);
        }
    }
}
