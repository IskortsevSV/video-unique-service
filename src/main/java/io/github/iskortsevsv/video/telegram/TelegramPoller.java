package io.github.iskortsevsv.video.telegram;

import io.github.iskortsevsv.video.config.TelegramProperties;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Получает обновления Telegram и сохраняет задания до подтверждения позиции. */
@Component
@ConditionalOnProperty(name = "telegram.enabled", havingValue = "true")
public class TelegramPoller {
    private static final Logger LOG = LoggerFactory.getLogger(TelegramPoller.class);
    private final TelegramClient client;
    private final TelegramHandler handler;
    private final Path state;
    private final BotJournal journal;
    private final BotJobs jobs;
    private final java.util.concurrent.atomic.AtomicBoolean stopping = new java.util.concurrent.atomic.AtomicBoolean();
    private java.nio.channels.FileChannel lockChannel;
    private java.nio.channels.FileLock instanceLock;
    private Thread worker;
    private volatile boolean running;

    public TelegramPoller(
            TelegramClient client, TelegramHandler handler, TelegramProperties config, BotJobs jobs) {
        this.jobs = jobs;
        this.client = client;
        this.handler = handler;
        this.state = config.offsetFile().toAbsolutePath();
        this.journal = new BotJournal(this.state);
    }

    @PostConstruct
    void start() throws IOException {
        Files.createDirectories(state.getParent());
        lockChannel =
                java.nio.channels.FileChannel.open(
                        state.resolveSibling(state.getFileName() + ".lock"),
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.WRITE);
        try {
            try {
                instanceLock = lockChannel.tryLock();
            } catch (java.nio.channels.OverlappingFileLockException ex) {
                throw new IllegalStateException(
                        "Telegram offset is already used by another poller");
            }
            if (instanceLock == null) {
                throw new IllegalStateException(
                        "Telegram offset is already used by another poller");
            }
            long initial =
                    Files.exists(state) ? Long.parseLong(Files.readString(state).strip()) : 0;
            if (initial < 0) {
                throw new IllegalArgumentException("Invalid Telegram offset");
            }
            running = true;
            worker =
                    Thread.ofPlatform().daemon().name("telegram-poller").start(() -> poll(initial));
        } catch (IOException | RuntimeException ex) {
            if (instanceLock != null) {
                instanceLock.release();
            }
            lockChannel.close();
            throw ex;
        }
    }

    private void poll(long offset) {
        LOG.info("Telegram polling enabled");
        boolean configured = false;
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                if (!configured) {
                    client.configureMenu();
                    configured = true;
                }
                for (var pending : journal.pending()) {
                    if (!running) {
                        return;
                    }
                    dispatch(pending);
                }
                journal.prune(offset);
                for (var update : client.updates(offset)) {
                    if (!running) {
                        return;
                    }
                    long next = update.path("update_id").asLong() + 1;
                    dispatch(update);
                    save(next);
                    offset = next;
                }
            } catch (Exception ex) {
                int delay = ex instanceof TelegramApiException api ? api.retrySeconds() : 5;
                LOG.warn(
                        "Telegram operation failed; retrying in {}s (details suppressed to protect"
                                + " token)",
                        delay);
                try {
                    Thread.sleep(delay * 1000L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void dispatch(tools.jackson.databind.JsonNode update) throws IOException {
        var item = journal.stage(update);
        if (item.done() || item.active()) {
            return;
        }
        BotJournal.set(item);
        try {
            handler.handle(update);
            item.finishDispatch();
        } finally {
            BotJournal.set(null);
        }
    }

    void save(long offset) throws IOException {
        Path temporary = state.resolveSibling(state.getFileName() + ".tmp");
        Files.writeString(temporary, Long.toString(offset));
        try {
            Files.move(
                    temporary,
                    state,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(temporary, state, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @PreDestroy
    void stop() {
        stop(java.time.Duration.ofSeconds(65));
    }

    void stop(java.time.Duration timeout) {
        if (instanceLock == null || !instanceLock.isValid() || !stopping.compareAndSet(false, true)) {
            return;
        }
        running = false;
        jobs.stop();
        if (worker != null) {
            worker.interrupt();
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        boolean terminated = false;
        try {
            if (worker != null) {
                worker.join(timeout);
            }
            terminated = (worker == null || !worker.isAlive())
                    && jobs.awaitTermination(java.time.Duration.ofNanos(Math.max(0, deadline - System.nanoTime())));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        if (terminated) {
            releaseLock();
        } else {
            LOG.warn("Telegram shutdown is still pending; journal lock remains held");
            Thread.ofPlatform().daemon().name("telegram-shutdown").start(this::releaseAfterTermination);
        }
    }

    private void releaseAfterTermination() {
        try {
            if (worker != null) {
                worker.join();
            }
            while (!jobs.awaitTermination(java.time.Duration.ofSeconds(30))) {
                // A blocked network operation must finish before another owner can recover jobs.
            }
            releaseLock();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            LOG.warn("Telegram shutdown waiter interrupted; journal lock remains held");
        }
    }

    private synchronized void releaseLock() {
        try {
            if (instanceLock != null && instanceLock.isValid()) {
                instanceLock.release();
            }
            if (lockChannel != null) {
                lockChannel.close();
            }
        } catch (IOException ex) {
            LOG.warn("Cannot release Telegram poller lock");
        }
    }
}
