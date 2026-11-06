package com.byron.davmusic;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Publishes complete snapshots without truncating the previously committed file. */
final class AtomicSnapshotWriter {
    private AtomicSnapshotWriter() {}

    @FunctionalInterface
    interface ContentWriter {
        void write(OutputStream out) throws IOException;
    }

    static void write(File target, String json) throws IOException {
        write(target, out -> out.write(json.getBytes(StandardCharsets.UTF_8)));
    }

    static void write(File target, ContentWriter writer) throws IOException {
        Path destination = target.toPath().toAbsolutePath();
        // Unique, same-directory files isolate concurrent writers and keep rename on one filesystem.
        Path temporary = Files.createTempFile(destination.getParent(), ".snapshot-", ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(temporary.toFile())) {
                writer.write(out);
                out.flush();
                out.getFD().sync();
            }
            // Fail closed if atomic replacement is unsupported: never copy/truncate as a fallback.
            Files.move(temporary, destination,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException | Error failure) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
        // Atomic visibility is not a promise of directory-entry durability after sudden power loss.
        // Process death before rename may leave an uncommitted temp file, never a partial target.
    }
}
