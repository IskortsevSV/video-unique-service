package io.github.iskortsevsv.video.telegram;

import io.github.iskortsevsv.video.dto.ProcessingOptions;
import io.github.iskortsevsv.video.service.HashService;
import io.github.iskortsevsv.video.service.PhotoProcessor;
import io.github.iskortsevsv.video.service.VideoProcessor;
import io.github.iskortsevsv.video.service.VideoService;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;

/** Организует скачивание, создание и отправку нескольких копий медиа. */
@Service
@ConditionalOnProperty(name = "telegram.enabled", havingValue = "true")
public class MediaBatchService {
    private final TelegramClient client;
    private final VideoService service;
    private final VideoProcessor videos;
    private final PhotoProcessor photos;
    private final HashService hashes;
    private final BotUserStore users;

    public MediaBatchService(
            TelegramClient client,
            VideoService service,
            VideoProcessor videos,
            PhotoProcessor photos,
            HashService hashes,
            BotUserStore users) {
        this.client = client;
        this.service = service;
        this.videos = videos;
        this.photos = photos;
        this.hashes = hashes;
        this.users = users;
    }

    /** Скачивает исходник, последовательно создаёт и отправляет копии, сообщая прогресс. */
    public void run(
            long chat,
            long reply,
            long progressMessage,
            String fileId,
            boolean photo,
            BotUserStore.Profile profile,
            ProcessingOptions base) {
        if (BotJournal.uncertain()) {
            client.message(
                    chat,
                    "⚠️ После сбоя неизвестно, была ли отправлена последняя копия. Проверьте чат:"
                        + " если её нет, отправьте исходный файл повторно. Автоматический повтор"
                        + " отключён, чтобы не создать дубликат.");
            return;
        }
        try (var progress = new BatchProgress(client, chat, progressMessage, profile.copies())) {
            try {
                service.withInput(
                        path -> client.download(fileId, path),
                        (input, workspace) -> {
                            String before = hashes.sha256(input);
                            Set<String> seen = new HashSet<>();
                            for (int i = 0; i < profile.copies(); i++) {
                                if (i < BotJournal.sent()) {
                                    progress.state(i, "Завершено ✅");
                                    continue;
                                }
                                if (Thread.currentThread().isInterrupted()) {
                                    throw new java.io.IOException("Interrupted");
                                }
                                int index = i;
                                var options = variant(base, i, profile.hard());
                                var output =
                                        workspace.file(
                                                "VUniq-" + (i + 1) + (photo ? ".jpg" : ".mp4"));
                                progress.state(i, "Обработка ⚙️");
                                if (photo) {
                                    photos.process(
                                            input, output, options.scale(), profile.hard(), i + 1);
                                } else {
                                    videos.process(
                                            input,
                                            output,
                                            options,
                                            profile.hard(),
                                            percent ->
                                                    progress.state(
                                                            index,
                                                            "Обработка ⚙️\n["
                                                                    + "■".repeat(percent / 10)
                                                                    + "□".repeat(10 - percent / 10)
                                                                    + "] "
                                                                    + percent
                                                                    + "%"));
                                }
                                String after = hashes.sha256(output);
                                if (after.equals(before) || !seen.add(after)) {
                                    throw new IllegalArgumentException(
                                            "Не удалось получить разные копии этого файла."
                                                    + " Попробуйте другие настройки.");
                                }
                                progress.state(i, "Отправка 📤");
                                String caption =
                                        "✨ <b>"
                                                + (photo ? "Фото" : "Видео")
                                                + " · копия "
                                                + (i + 1)
                                                + "/"
                                                + profile.copies()
                                                + "</b>\nРежим: "
                                                + (profile.hard() ? "🔥 Hard" : "Basic")
                                                + "\n\n▫️ SHA-256 до:\n<code>"
                                                + before
                                                + "</code>\n▫️ SHA-256 после:\n<code>"
                                                + after
                                                + "</code>\n\n"
                                                + (photo
                                                        ? ""
                                                        : "Скорость: " + options.speed() + "x\n")
                                                + "Масштаб: "
                                                + options.scale()
                                                + "x\n\n"
                                                + "✅ Обработка завершена.\n"
                                                + "Чёрные поля — результат масштаба. Разные хеши не"
                                                + " гарантируют уникальность для соцсетей.";
                                sendWithRetry(chat, reply, output, caption, photo);
                                BotJournal.delivered(i + 1);
                                progress.state(i, "Завершено ✅");
                                users.delivered(chat, i == 0);
                                Files.deleteIfExists(output);
                            }
                        });
                var current = users.get(chat);
                client.screen(
                        chat,
                        "✅ <b>Готово! Все копии отправлены.</b>\n\n"
                                + "В этом месяце: "
                                + current.originals()
                                + " исходников и "
                                + current.results()
                                + " копий.\n"
                                + (current.originals() < 3
                                        ? "🎁 В демонстрационном счётчике осталось "
                                                + (3 - current.originals())
                                                + " из 3 исходников."
                                        : "🎁 Демонстрационные 3 исходника использованы. Тестовый"
                                                + " доступ остаётся бесплатным.")
                                + "\n\nОтправь следующий файл или измени настройки 👇",
                        BotScreens.homeKeys());
            } catch (Exception ex) {
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
                progress.failRemaining();
                String detail =
                        BotJournal.uncertain()
                                ? "Не удалось подтвердить доставку последней копии. Проверьте чат"
                                        + " перед повторной отправкой исходника."
                                : ex instanceof IllegalArgumentException
                                        ? ex.getMessage()
                                        : "Не удалось завершить все копии. Уже отправленные"
                                                + " доступны в чате. Попробуйте позже или отправьте"
                                                + " меньший файл.";
                // If notification fails, keep the journal entry so it can be retried.
                client.message(chat, "⚠️ " + detail);
            }
        }
    }

    private void sendWithRetry(
            long chat, long reply, java.nio.file.Path output, String caption, boolean photo)
            throws java.io.IOException {
        if (Files.size(output) > TelegramClient.MAX_OUTPUT) {
            throw new IllegalArgumentException("Результат превышает лимит Telegram 50 МБ.");
        }
        for (int attempt = 0; ; attempt++) {
            BotJournal.sending(true);
            try {
                client.result(chat, reply, output, caption, photo);
                return;
            } catch (TelegramApiException ex) {
                if (!ex.rateLimited()) {
                    throw ex;
                }
                // A 429 response confirms that this attempt was rejected.
                BotJournal.sending(false);
                if (attempt >= 2) {
                    throw ex;
                }
                try {
                    Thread.sleep(ex.retrySeconds() * 1000L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new java.io.IOException("Delivery retry interrupted");
                }
            }
        }
    }

    static ProcessingOptions variant(ProcessingOptions base, int index, boolean hard) {
        double step = (hard ? .006 : .003) * index;
        double speed = base.speed() + step <= 2 ? base.speed() + step : base.speed() - step;
        double scale = base.scale() - step >= .5 ? base.scale() - step : base.scale() + step;
        return new ProcessingOptions(
                Math.round(speed * 10000) / 10000.0, Math.round(scale * 10000) / 10000.0);
    }
}
