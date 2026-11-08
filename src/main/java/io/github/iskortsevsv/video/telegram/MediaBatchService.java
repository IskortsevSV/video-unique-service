package io.github.iskortsevsv.video.telegram;

import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import io.github.iskortsevsv.video.dto.ProcessingOptions;
import io.github.iskortsevsv.video.service.HashService;
import io.github.iskortsevsv.video.service.PhotoProcessor;
import io.github.iskortsevsv.video.service.VideoProcessor;
import io.github.iskortsevsv.video.service.VideoService;

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
        try (var progress = new BatchProgress(client, chat, progressMessage, profile.copies())) {
            try {
                service.withInput(
                        path -> client.download(fileId, path),
                        (input, workspace) -> {
                            String before = hashes.sha256(input);
                            Set<String> seen = new HashSet<>();
                            for (int i = 0; i < profile.copies(); i++) {
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
                                            "Не удалось получить разные копии этого файла. Попробуйте другие настройки.");
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
                                                + "x\n\n✅ Обработка завершена.\n"
                                                + "Чёрные поля — результат масштаба. Разные хеши не гарантируют уникальность для соцсетей.";
                                client.result(chat, reply, output, caption, photo);
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
                                        : "🎁 Демонстрационные 3 исходника использованы. Тестовый доступ остаётся бесплатным.")
                                + "\n\nОтправь следующий файл или измени настройки 👇",
                        BotScreens.homeKeys());
            } catch (Exception ex) {
                progress.failRemaining();
                String detail =
                        ex instanceof IllegalArgumentException
                                ? ex.getMessage()
                                : "Не удалось завершить все копии. Уже отправленные доступны в чате. Попробуйте позже или отправьте меньший файл.";
                try {
                    client.message(chat, "⚠️ " + detail);
                } catch (TelegramApiException ignored) {
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
