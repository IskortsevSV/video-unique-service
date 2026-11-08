package io.github.iskortsevsv.video.telegram;

import io.github.iskortsevsv.video.config.TelegramProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "telegram.enabled", havingValue = "true")
public class TelegramPoller {
    private static final Logger LOG = LoggerFactory.getLogger(TelegramPoller.class);
    private final TelegramClient client;
    private final TelegramHandler handler;
    private final Path state;
    private Thread worker;
    private volatile boolean running;
    public TelegramPoller(TelegramClient client, TelegramHandler handler, TelegramProperties config) {
        this.client = client; this.handler = handler; this.state = config.offsetFile().toAbsolutePath();
    }
    @PostConstruct void start() throws IOException {
        Files.createDirectories(state.getParent());
        long initial = Files.exists(state) ? Long.parseLong(Files.readString(state).strip()) : 0;
        if (initial < 0) throw new IllegalArgumentException("Invalid Telegram offset");
        running = true;
        worker = Thread.ofPlatform().daemon().name("telegram-poller").start(() -> poll(initial));
    }
    private void poll(long offset) {
        LOG.info("Telegram polling enabled");
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                for (var update : client.updates(offset)) {
                    if (!running) return;
                    long next = update.path("update_id").asLong() + 1;
                    // Persist before effects: no automatic duplicate processing after restart.
                    save(next);
                    offset = next;
                    handler.handle(update);
                }
            } catch (Exception ex) {
                int delay = ex instanceof TelegramApiException api ? api.retrySeconds() : 5;
                LOG.warn("Telegram operation failed; retrying in {}s (details suppressed to protect token)", delay);
                try { Thread.sleep(delay * 1000L); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
        }
    }
    void save(long offset) throws IOException {
        Path temporary = state.resolveSibling(state.getFileName() + ".tmp");
        Files.writeString(temporary, Long.toString(offset));
        try { Files.move(temporary, state, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException ex) { Files.move(temporary, state, StandardCopyOption.REPLACE_EXISTING); }
    }
    @PreDestroy void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
            try { worker.join(65000); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        }
    }
}
