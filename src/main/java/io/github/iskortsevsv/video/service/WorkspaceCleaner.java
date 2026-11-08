package io.github.iskortsevsv.video.service;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Находит и удаляет оставленные после завершения процесса рабочие каталоги. */
@Component
public class WorkspaceCleaner {
    private static final Logger LOG = LoggerFactory.getLogger(WorkspaceCleaner.class);
    private final Path root;
    private final ScheduledExecutorService timer =
            Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon().name("workspace-cleaner").factory());

    public WorkspaceCleaner() {
        this(Path.of(System.getProperty("java.io.tmpdir")));
    }

    WorkspaceCleaner(Path root) {
        this.root = root;
    }

    @PostConstruct
    void start() {
        sweep();
        timer.scheduleWithFixedDelay(this::sweep, 5, 5, TimeUnit.MINUTES);
    }

    void sweep() {
        synchronized (TemporaryWorkspace.GUARD_MONITOR) {
            try (var guard =
                    FileChannel.open(
                            root.resolve(TemporaryWorkspace.GUARD),
                            StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE,
                            LinkOption.NOFOLLOW_LINKS)) {
                FileLock acquired;
                try {
                    acquired = guard.tryLock();
                } catch (OverlappingFileLockException ex) {
                    return;
                }
                if (acquired == null) {
                    return;
                }
                try (acquired;
                        var entries =
                                Files.newDirectoryStream(root, TemporaryWorkspace.PREFIX + "*")) {
                    for (Path path : entries) {
                        try {
                            TemporaryWorkspace.cleanupAbandoned(path);
                        } catch (IOException | SecurityException ex) {
                            LOG.warn("Could not remove a temporary workspace; will retry later");
                        }
                    }
                }
            } catch (IOException | SecurityException ex) {
                LOG.warn("Could not scan temporary workspaces; will retry later");
            }
        }
    }

    @PreDestroy
    void stop() {
        timer.shutdownNow();
    }
}
