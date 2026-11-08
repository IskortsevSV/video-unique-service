package io.github.iskortsevsv.video.telegram;

import jakarta.annotation.PreDestroy;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Ограничивает очередь заданий и число одновременно активных заданий чата. */
@Component
public class BotJobs {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(BotJobs.class);
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
        var item = BotJournal.current();
        if (item != null) {
            item.scheduled();
        }
        try {
            workers.execute(
                    () -> {
                        BotJournal.set(item);
                        try {
                            action.run();
                            if (item != null && !Thread.currentThread().isInterrupted()) {
                                item.complete();
                            }
                        } catch (java.io.IOException ex) {
                            LOG.warn("Cannot persist Telegram job state; recovery will retry");
                        } finally {
                            BotJournal.set(null);
                            busy.remove(chat);
                            if (item != null) {
                                item.finishWorker();
                            }
                        }
                    });
            return true;
        } catch (RejectedExecutionException ex) {
            if (item != null) {
                item.rejected();
            }
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
