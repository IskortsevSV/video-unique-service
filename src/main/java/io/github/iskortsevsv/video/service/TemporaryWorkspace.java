package io.github.iskortsevsv.video.service;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.Comparator;
import java.util.UUID;

public final class TemporaryWorkspace implements AutoCloseable {
    static final String PREFIX = "vuniq-managed-";
    static final String LEASE = ".vuniq-lease";
    static final String GUARD = "vuniq-workspace.guard";
    static final Object GUARD_MONITOR = new Object();
    private final Path directory;
    private final FileChannel channel;
    private final FileLock lock;

    public TemporaryWorkspace() throws IOException { this(Path.of(System.getProperty("java.io.tmpdir"))); }
    TemporaryWorkspace(Path root) throws IOException {
        synchronized (GUARD_MONITOR) {
        try (var guard = FileChannel.open(root.resolve(GUARD), StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
             var guardLock = guard.lock()) {
        directory = Files.createDirectory(root.resolve(PREFIX + UUID.randomUUID()));
        FileChannel opened = null;
        try {
            opened = FileChannel.open(directory.resolve(LEASE), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            lock = opened.lock();
            channel = opened;
        } catch (IOException | RuntimeException ex) {
            if (opened != null) opened.close();
            Files.deleteIfExists(directory.resolve(LEASE));
            Files.deleteIfExists(directory);
            throw ex;
        }
        }
        }
    }
    public Path file(String name) { return directory.resolve(name); }
    @Override public void close() throws IOException {
        // Keep the lease if deletion fails so the periodic cleaner can retry.
        boolean cleaned = false;
        try { deleteContents(directory); cleaned = true; }
        finally { try { lock.release(); } finally { channel.close(); } }
        if (cleaned) {
            Files.deleteIfExists(directory.resolve(LEASE));
            Files.deleteIfExists(directory);
        }
    }
    static void deleteContents(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.equals(directory) && !path.equals(directory.resolve(LEASE))) Files.deleteIfExists(path);
            }
        }
    }
    static boolean cleanupAbandoned(Path directory) throws IOException {
        if (!directory.getFileName().toString().startsWith(PREFIX)
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return false;
        Path lease = directory.resolve(LEASE);
        if (!Files.isRegularFile(lease, LinkOption.NOFOLLOW_LINKS)) return false;
        try (var channel = FileChannel.open(lease, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            FileLock lock;
            try { lock = channel.tryLock(); } catch (OverlappingFileLockException ex) { return false; }
            if (lock == null) return false;
            try (lock) { deleteContents(directory); }
        }
        Files.deleteIfExists(lease);
        Files.deleteIfExists(directory);
        return true;
    }
}
