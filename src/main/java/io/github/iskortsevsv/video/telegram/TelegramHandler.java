package io.github.iskortsevsv.video.telegram;

import io.github.iskortsevsv.video.config.TelegramProperties;
import io.github.iskortsevsv.video.dto.ProcessingOptions;
import io.github.iskortsevsv.video.exception.VideoException;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Маршрутизирует сообщения и действия пользователей Telegram-бота. */
@Component
@ConditionalOnProperty(name = "telegram.enabled", havingValue = "true")
public class TelegramHandler {
    private final TelegramClient client;
    private final TelegramProperties config;
    private final BotUserStore users;
    private final BotJobs jobs;
    private final MediaBatchService batches;

    public TelegramHandler(
            TelegramClient client,
            TelegramProperties config,
            BotUserStore users,
            BotJobs jobs,
            MediaBatchService batches) {
        this.client = client;
        this.config = config;
        this.users = users;
        this.jobs = jobs;
        this.batches = batches;
    }

    /** Обрабатывает разрешённое обновление из личного чата или callback-запрос. */
    public void handle(JsonNode update) {
        boolean callback = update.has("callback_query");
        var query = update.path("callback_query");
        var message = callback ? query.path("message") : update.path("message");
        var chat = message.path("chat");
        if (!chat.path("type").asText().equals("private")) {
            return;
        }
        long id = chat.path("id").asLong();
        if (!config.allowedChatIds().isEmpty() && !config.allowedChatIds().contains(id)) {
            return;
        }
        if (callback && query.path("from").path("id").asLong() != id) {
            return;
        }
        try {
            if (callback) {
                client.answer(query.path("id").asText());
            }
            String action =
                    callback
                            ? query.path("data").asText()
                            : message.path("text").asText("").split("\\s+")[0].split("@")[0];
            var profile = users.get(id);
            if (action.startsWith("mode:")) {
                profile = users.settings(id, action.substring(5), profile.copies());
                action = "settings";
            } else if (action.startsWith("copies:")) {
                profile = users.settings(id, profile.mode(), Integer.parseInt(action.substring(7)));
                action = "settings";
            }
            switch (action) {
                case "/id" -> {
                    client.message(id, "ID чата: " + id);
                    return;
                }
                case "/settings", "settings" -> {
                    show(
                            id,
                            callback,
                            message,
                            BotScreens.settings(profile),
                            BotScreens.settingsKeys(profile));
                    return;
                }
                case "/subscription", "subscription" -> {
                    show(
                            id,
                            callback,
                            message,
                            BotScreens.subscription(profile),
                            BotScreens.subscriptionKeys());
                    return;
                }
                case "pay:basic", "pay:pro" -> {
                    client.screen(
                            id,
                            "🧪 <b>Оплата пока не подключена</b>\n\n"
                                + "Это демонстрационная кнопка. Деньги не списываются, подписка не"
                                + " оформляется.\n"
                                + "Оба режима и до 10 копий доступны бесплатно — просто отправь"
                                + " файл.",
                            BotScreens.homeKeys());
                    return;
                }
                case "/help", "help", "upload" -> {
                    show(id, callback, message, BotScreens.help(), BotScreens.backKeys());
                    return;
                }
                case "/start", "/menu", "home" -> {
                    show(
                            id,
                            callback,
                            message,
                            BotScreens.home(chat.path("first_name").asText("друг")),
                            BotScreens.homeKeys());
                    return;
                }
                default -> {
                    if (callback) {
                        client.message(id, "Кнопка устарела. Откройте /start.");
                        return;
                    }
                }
            }
            boolean photo = message.has("photo");
            var media =
                    photo
                            ? message.path("photo").get(message.path("photo").size() - 1)
                            : message.has("video")
                                    ? message.path("video")
                                    : message.path("document");
            if (media == null || !media.has("file_id")) {
                client.screen(id, BotScreens.help(), BotScreens.homeKeys());
                return;
            }
            if (media.path("file_size").asLong(0) > TelegramClient.MAX_INPUT) {
                throw new IllegalArgumentException(
                        "Файл превышает 20 МБ. Отправьте файл меньшего размера.");
            }
            String name =
                    media.path("file_name")
                            .asText(photo ? "photo.jpg" : message.has("video") ? "video.mp4" : "")
                            .toLowerCase(Locale.ROOT);
            photo = photo || name.matches(".*\\.(jpg|jpeg|png)$");
            if (!photo && !name.matches(".*\\.(mp4|mov)$")) {
                throw new IllegalArgumentException("Отправьте MP4/MOV или JPEG/PNG.");
            }
            String caption = message.path("caption").asText("");
            var options =
                    caption.isBlank() && profile.hard()
                            ? new ProcessingOptions(1.04, .94)
                            : options(caption);
            var snapshot = BotJournal.snapshot(profile, options);
            profile =
                    new BotUserStore.Profile(
                            snapshot.mode(),
                            snapshot.copies(),
                            profile.originals(),
                            profile.results(),
                            profile.month());
            var savedOptions = new ProcessingOptions(snapshot.speed(), snapshot.scale());
            long progress =
                    client.screen(
                            id,
                            "⏳ <b>Файл принят</b>\nКопий: "
                                    + profile.copies()
                                    + " · Режим: "
                                    + (profile.hard() ? "Hard" : "Basic")
                                    + "\nОжидает обработки…",
                            Map.of("inline_keyboard", List.of()));
            var selected = profile;
            boolean isPhoto = photo;
            String fileId = media.path("file_id").asText();
            if (!jobs.submit(
                    id,
                    () ->
                            batches.run(
                                    id,
                                    message.path("message_id").asLong(),
                                    progress,
                                    fileId,
                                    isPhoto,
                                    selected,
                                    savedOptions))) {
                client.edit(
                        id,
                        progress,
                        "⏳ У вас уже есть задание или очередь заполнена. Дождитесь результата и"
                            + " отправьте файл снова.",
                        BotScreens.homeKeys());
            }
        } catch (IllegalArgumentException ex) {
            client.message(id, "⚠️ Проверьте параметры. " + ex.getMessage());
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException("Cannot persist Telegram state", ex);
        }
    }

    private void show(long chat, boolean callback, JsonNode message, String text, Object keyboard) {
        if (!callback) {
            client.screen(chat, text, keyboard);
        } else {
            try {
                client.edit(chat, message.path("message_id").asLong(), text, keyboard);
            } catch (TelegramApiException ex) {
                // Repeated selection or an uneditable old message: nothing needs to be retried.
                if (ex.errorCode() != 400) {
                    throw ex;
                }
            }
        }
    }

    static ProcessingOptions options(String caption) {
        double speed = 1.01, scale = .98;
        Set<String> seen = new HashSet<>();
        if (!caption.isBlank()) {
            for (String item : caption.strip().split("\\s+")) {
                String[] pair = item.split("=", -1);
                if (pair.length != 2
                        || !Set.of("speed", "scale").contains(pair[0])
                        || !seen.add(pair[0])) {
                    throw new IllegalArgumentException(
                            "Подпись должна иметь вид: speed=1.01 scale=0.98");
                }
                double value;
                try {
                    value = Double.parseDouble(pair[1]);
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException(
                            "Параметры должны быть числами, например 1.01.");
                }
                if (pair[0].equals("speed")) {
                    speed = value;
                } else {
                    scale = value;
                }
            }
        }
        try {
            return new ProcessingOptions(speed, scale);
        } catch (VideoException ex) {
            throw new IllegalArgumentException(
                    "Допустимые параметры: speed от 0.5 до 2.0, scale от 0.5 до 1.0.");
        }
    }
}
