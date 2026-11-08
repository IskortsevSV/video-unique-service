package io.github.iskortsevsv.video.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceCleanerTest {
    @TempDir Path root;

    @Test void leavesActiveWorkspacesAndUnrelatedFilesAlone() throws Exception {
        Path unrelated = Files.createDirectory(root.resolve("other"));
        Path lookalike = Files.createDirectory(root.resolve(TemporaryWorkspace.PREFIX + "unowned"));
        Files.writeString(lookalike.resolve("private.txt"), "leave alone");
        try (var workspace = new TemporaryWorkspace(root)) {
            Path active = workspace.file("input.mp4"); Files.writeString(active, "active");
            new WorkspaceCleaner(root).sweep();
            assertTrue(Files.exists(active));
            assertTrue(Files.exists(lookalike.resolve("private.txt")));
            assertTrue(Files.exists(unrelated));
        }
    }
    @Test void recoversFilesAfterForcedProcessTermination() throws Exception {
        Path ready = root.resolve("ready");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                CrashWorker.class.getName(), root.toString(), ready.toString()).redirectErrorStream(true).start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!Files.exists(ready) && process.isAlive() && System.nanoTime() < deadline) Thread.sleep(50);
            assertTrue(Files.exists(ready), "Child failed to create workspace");
            Path media = Path.of(Files.readString(ready));
            var cleaner = new WorkspaceCleaner(root);
            cleaner.sweep();
            assertTrue(Files.exists(media), "A live process still owns this workspace");
            process.destroyForcibly();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            cleaner.sweep();
            assertFalse(Files.exists(media));
            assertFalse(Files.exists(media.getParent()));
        } finally { process.destroyForcibly(); process.waitFor(); }
    }
    public static class CrashWorker {
        public static void main(String[] args) throws Exception {
            var workspace = new TemporaryWorkspace(Path.of(args[0]));
            Path media = workspace.file("input.mp4");
            Files.writeString(media, "temporary video");
            Files.writeString(Path.of(args[1]), media.toString());
            Thread.sleep(60000);
        }
    }
}
