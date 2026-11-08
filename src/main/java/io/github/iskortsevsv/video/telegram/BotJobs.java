package io.github.iskortsevsv.video.telegram;

import jakarta.annotation.PreDestroy;
import java.util.Set;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;

@Component
public class BotJobs {
    private final Set<Long> busy = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(2), Thread.ofPlatform().daemon().name("bot-media-", 0).factory());
    public boolean submit(long chat, Runnable action) {
        if (!busy.add(chat)) return false;
        try {
            workers.execute(() -> { try { action.run(); } finally { busy.remove(chat); } });
            return true;
        } catch (RejectedExecutionException ex) { busy.remove(chat); return false; }
    }
    @PreDestroy public void stop() { workers.shutdownNow(); }
}
