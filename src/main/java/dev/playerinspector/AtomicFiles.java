package dev.playerinspector;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/** File write boundary independent of Bukkit; writes fully to a synced temp file before publishing. */
final class AtomicFiles {
    private AtomicFiles() {}

    static void replace(Path destination, String content) throws IOException {
        publish(destination, content, false);
    }

    static void createNew(Path destination, String content) throws IOException {
        publish(destination, content, true);
    }

    private static void publish(Path destination, String content, boolean immutable) throws IOException {
        Files.createDirectories(destination.getParent());
        Path temporary = destination.resolveSibling(destination.getFileName() + "."
                + UUID.randomUUID().toString().replace("-", "") + ".tmp");
        try {
            try (FileChannel ch = FileChannel.open(temporary,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = StandardCharsets.UTF_8.encode(content);
                while (bytes.hasRemaining()) ch.write(bytes);
                ch.force(true);
            }
            if (immutable) {
                if (Files.exists(destination)) throw new FileAlreadyExistsException(destination.toString());
                try {
                    // Hard-link publication is atomic and never replaces a file (on supporting filesystems).
                    Files.createLink(destination, temporary);
                } catch (UnsupportedOperationException | java.nio.file.FileSystemException ex) {
                    if (ex instanceof FileAlreadyExistsException) throw ex;
                    if (Files.exists(destination)) throw new FileAlreadyExistsException(destination.toString());
                    try {
                        Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
                    } catch (AtomicMoveNotSupportedException unsupported) {
                        Files.move(temporary, destination);
                    }
                }
            } else {
                try {
                    Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ex) {
                    Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            try (FileChannel directory = FileChannel.open(destination.getParent(), StandardOpenOption.READ)) {
                directory.force(true);
            } catch (IOException | UnsupportedOperationException ignored) {
                // Directory fsync may be unavailable (notably on some Windows filesystems).
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
