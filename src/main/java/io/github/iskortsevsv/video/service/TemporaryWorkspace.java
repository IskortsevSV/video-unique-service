package io.github.iskortsevsv.video.service;

import java.io.IOException;
import java.nio.file.*;
import java.util.Comparator;

public final class TemporaryWorkspace implements AutoCloseable {
    private final Path directory;
    public TemporaryWorkspace() throws IOException { directory = Files.createTempDirectory("video-unique-"); }
    public Path file(String name) { return directory.resolve(name); }
    @Override public void close() throws IOException {
        try (var paths = Files.walk(directory)) {
            IOException failure = null;
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                try { Files.deleteIfExists(path); } catch (IOException ex) { failure = ex; }
            }
            if (failure != null) throw failure;
        }
    }
}
