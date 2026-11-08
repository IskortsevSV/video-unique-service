package io.github.iskortsevsv.video.telegram;

import io.github.iskortsevsv.video.config.TelegramProperties;
import io.github.iskortsevsv.video.dto.ProcessingOptions;
import io.github.iskortsevsv.video.exception.VideoException;
import io.github.iskortsevsv.video.service.VideoService;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
@ConditionalOnProperty(name = "telegram.enabled", havingValue = "true")
public class TelegramHandler {
    static final String HELP = "Отправьте видео или файл MP4/MOV до 20 МБ. По умолчанию: speed=1.01 scale=0.98. "
            + "Параметры можно указать в подписи, например: speed=1.05 scale=0.95. "
            + "speed: 0.5–2.0, scale: 0.5–1.0. Результат придёт файлом MP4. "
            + "Разные SHA-256 подтверждают различие байтов, но не гарантируют уникальность для соцсетей. "
            + "Для сохранения исходных байтов отправляйте видео как файл. /id — ID чата.";
    private final TelegramClient client;
    private final VideoService service;
    private final TelegramProperties config;
    public TelegramHandler(TelegramClient client, VideoService service, TelegramProperties config) {
        this.client = client; this.service = service; this.config = config;
    }
    public void handle(JsonNode update) {
        var message = update.path("message");
        var chat = message.path("chat");
        if (!chat.path("type").asText().equals("private")) return;
        long id = chat.path("id").asLong();
        if (!config.allowedChatIds().isEmpty() && !config.allowedChatIds().contains(id)) return;
        String text = message.path("text").asText("");
        if (text.equals("/id")) { client.message(id, "ID чата: " + id); return; }
        var media = message.has("video") ? message.path("video") : message.path("document");
        if (!media.has("file_id")) { client.message(id, HELP); return; }
        try {
            if (media.path("file_size").asLong(0) > TelegramClient.MAX_INPUT)
                throw new IllegalArgumentException("Telegram позволяет скачать файл не больше 20 МБ. Отправьте файл меньшего размера.");
            String name = media.path("file_name").asText(message.has("video") ? "video.mp4" : "");
            if (!name.toLowerCase(Locale.ROOT).matches(".*\\.(mp4|mov)$"))
                throw new IllegalArgumentException("Отправьте файл MP4 или MOV.");
            var options = options(message.path("caption").asText(""));
            client.message(id, "Видео принято. Обрабатываю…");
            service.process(name, path -> client.download(media.path("file_id").asText(), path), options,
                    (output, before, after) -> client.document(id, output,
                            "Видео обработано ✅\nХеш до: " + before + "\nХеш после: " + after
                            + "\nСкорость: " + options.speed() + "x\nМасштаб: " + options.scale()
                            + "x\nSHA-256 показывает различие байтов; уникальность для соцсетей не гарантируется."));
        } catch (IllegalArgumentException ex) { client.message(id, ex.getMessage()); }
        catch (VideoException ex) {
            String error = switch (ex.status().value()) {
                case 429 -> "Сервис занят. Отправьте видео немного позже.";
                case 504 -> "Обработка заняла слишком много времени. Попробуйте более короткое видео.";
                case 422 -> "Не удалось прочитать видео. Нужен корректный MP4/MOV с видеодорожкой.";
                default -> "Не удалось обработать видео. Проверьте файл или попробуйте позже.";
            };
            client.message(id, error);
        } catch (TelegramApiException ex) { throw ex; }
        catch (Exception ex) { client.message(id, "Ошибка обработки файла. Попробуйте позже."); }
    }
    static ProcessingOptions options(String caption) {
        double speed = 1.01, scale = .98;
        Set<String> seen = new HashSet<>();
        if (!caption.isBlank()) for (String item : caption.strip().split("\\s+")) {
            String[] pair = item.split("=", -1);
            if (pair.length != 2 || !Set.of("speed", "scale").contains(pair[0]) || !seen.add(pair[0]))
                throw new IllegalArgumentException("Подпись должна иметь вид: speed=1.01 scale=0.98");
            double value;
            try { value = Double.parseDouble(pair[1]); }
            catch (NumberFormatException ex) { throw new IllegalArgumentException("Параметры должны быть числами, например 1.01."); }
            if (pair[0].equals("speed")) speed = value; else scale = value;
        }
        try { return new ProcessingOptions(speed, scale); }
        catch (VideoException ex) { throw new IllegalArgumentException("Допустимые параметры: speed от 0.5 до 2.0, scale от 0.5 до 1.0."); }
    }
}
