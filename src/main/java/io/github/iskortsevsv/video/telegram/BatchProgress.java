package io.github.iskortsevsv.video.telegram;

import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Отслеживает состояния копий и периодически обновляет сообщение Telegram. */
final class BatchProgress implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(BatchProgress.class);
    private final TelegramClient client;
    private final long chat, message;
    private final String[] states;
    private String sent = "";
    private long retryAfter;
    private final Object sendLock = new Object();
    private final ScheduledExecutorService timer =
            Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon().name("bot-progress").factory());

    BatchProgress(TelegramClient client, long chat, long message, int count) {
        this.client = client;
        this.chat = chat;
        this.message = message;
        states = new String[count];
        Arrays.fill(states, "В очереди ⏳");
        timer.scheduleWithFixedDelay(this::flush, 1, 3, TimeUnit.SECONDS);
    }

    synchronized void state(int index, String value) {
        states[index] = value;
    }

    synchronized void failRemaining() {
        for (int i = 0; i < states.length; i++) {
            if (!states[i].equals("Завершено ✅")) {
                states[i] = "Не завершено ⚠️";
            }
        }
    }

    synchronized String text() {
        var result = new StringBuilder("🔎 <b>Прогресс обработки</b>\n");
        for (int i = 0; i < states.length; i++) {
            result.append("\n▫️ Копия ")
                    .append(i + 1)
                    .append('/')
                    .append(states.length)
                    .append(": ")
                    .append(states[i])
                    .append('\n');
        }
        return result.toString();
    }

    // Network work stays off the FFmpeg stdout reader and does not block its pipe.
    void flush() {
        synchronized (sendLock) {
            if (System.nanoTime() < retryAfter) {
                return;
            }
            String current = text();
            if (current.equals(sent)) {
                return;
            }
            try {
                client.edit(
                        chat,
                        message,
                        current,
                        java.util.Map.of("inline_keyboard", java.util.List.of()));
                sent = current;
            } catch (TelegramApiException ex) {
                LOG.warn(
                        "Не удалось обновить прогресс в Telegram; повторим после паузы {} с",
                        ex.retrySeconds());
                retryAfter = System.nanoTime() + TimeUnit.SECONDS.toNanos(ex.retrySeconds());
            }
        }
    }

    @Override
    public void close() {
        timer.shutdownNow();
        flush();
    }
}
