package io.github.iskortsevsv.video.telegram;

import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

/** Ограничивает очередь заданий и число одновременно активных заданий чата. */
@Component
public class BotJobs {
    private final Set<Long> busy = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor workers =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(2),
                    Thread.ofPlatform().daemon().name("bot-media-", 0).factory());

    /** Добавляет задание в ограниченную очередь, если у чата нет активного задания. */
    public boolean submit(long chat, Runnable action) {
        if (!busy.add(chat)) {
            return false;
        }
        try {
            workers.execute(
                    () -> {
                        try {
                            action.run();
                        } finally {
                            busy.remove(chat);
                        }
                    });
            return true;
        } catch (RejectedExecutionException ex) {
            busy.remove(chat);
            return false;
        }
    }

    /** Останавливает очередь и прерывает исполняемые задания. */
    @PreDestroy
    public void stop() {
        workers.shutdownNow();
    }
}
