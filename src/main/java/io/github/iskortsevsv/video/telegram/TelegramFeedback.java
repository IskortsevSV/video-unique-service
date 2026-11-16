package io.github.iskortsevsv.video.telegram;

import io.github.iskortsevsv.video.config.TelegramProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Связывает ответы в Telegram с выданной копией и формирует сводку для администратора. */
@Component
@ConditionalOnProperty(name = "telegram.enabled", havingValue = "true")
class TelegramFeedback {
    private static final Map<String, String> RESULTS = Map.of(
            "ok", "Без предупреждения", "dup", "Распознан повтор",
            "err", "Другая ошибка", "wait", "Ещё не проверял");
    private static final Map<String, String> FORMATS = Map.of(
            "trial", "Пробный Reels", "reel", "Обычный Reels", "post", "Пост",
            "carousel", "Карусель", "other", "Другая площадка");

    private final TelegramClient client;
    private final TelegramProperties config;
    private final FeedbackStore store;

    TelegramFeedback(TelegramClient client, TelegramProperties config, FeedbackStore store) {
        this.client = client;
        this.config = config;
        this.store = store;
    }

    static Object resultButton(String id) {
        return keys(List.of(BotScreens.button("📊 Сообщить результат публикации", "fb:" + id)));
    }

    static Object keys(List<Map<String, String>> buttons) {
        return Map.of("inline_keyboard", buttons.stream().map(List::of).toList());
    }

    void showStats(long chat) throws IOException {
        if (!config.feedbackAdminIds().contains(chat)) {
            client.message(chat, "Команда доступна администратору.");
            return;
        }
        var report = store.stats(Instant.now());
        var chunk = new StringBuilder("Обратная связь за 30 дней по дате выдачи копии. "
                + "Результаты сообщают пользователи.");
        if (report.isEmpty()) {
            chunk.append("\nВыданных копий пока нет.");
        }
        for (String line : report) {
            if (chunk.length() + line.length() + 2 > 3900) {
                client.message(chat, chunk.toString());
                chunk.setLength(0);
            }
            chunk.append("\n\n").append(line);
        }
        client.message(chat, chunk.toString());
    }

    void handle(long chat, JsonNode message, String action) throws IOException {
        var fields = action.split(":", -1);
        if (fields.length < 2 || fields.length > 5 || !message.path("from").path("is_bot").asBoolean(false)) {
            throw new IllegalArgumentException("Кнопка обратной связи недействительна.");
        }
        String id = fields[1];
        var row = store.owned(id, chat);
        long messageId = message.path("message_id").asLong();
        store.delivered(id, chat);
        String prefix = "fb:" + id;
        if (fields.length == 2) {
            client.editButtons(chat, messageId, keys(List.of(
                    BotScreens.button("✅ " + RESULTS.get("ok"), prefix + ":ok"),
                    BotScreens.button("🔁 " + RESULTS.get("dup"), prefix + ":dup"),
                    BotScreens.button("⚠️ " + RESULTS.get("err"), prefix + ":err"),
                    BotScreens.button("⏳ " + RESULTS.get("wait"), prefix + ":wait"),
                    BotScreens.button("← Назад", prefix + ":back"))));
            return;
        }
        String result = fields[2];
        if ("back".equals(result) && fields.length == 3) {
            showSaved(chat, messageId, id, row.getProperty("result"));
            return;
        }
        if (!RESULTS.containsKey(result)) {
            throw new IllegalArgumentException("Неизвестный результат публикации.");
        }
        if ("wait".equals(result) && fields.length == 3) {
            store.vote(id, chat, result, "", "");
            showSaved(chat, messageId, id, result);
            return;
        }
        boolean photo = "Фото".equals(row.getProperty("media"));
        var allowed = photo ? List.of("post", "carousel", "other") : List.of("trial", "reel", "other");
        if (fields.length == 3) {
            client.editButtons(chat, messageId, keys(allowed.stream()
                    .map(format -> BotScreens.button(FORMATS.get(format), prefix + ":" + result + ":" + format))
                    .toList()));
            return;
        }
        String format = fields[3];
        if ("wait".equals(result) || !allowed.contains(format)) {
            throw new IllegalArgumentException("Формат публикации не подходит.");
        }
        if (fields.length == 4) {
            String choice = action + ":";
            client.editButtons(chat, messageId, keys(List.of(
                    BotScreens.button("Исходник уже публиковался", choice + "yes"),
                    BotScreens.button("Это первая публикация исходника", choice + "no"),
                    BotScreens.button("Пропустить вопрос об исходнике", choice + "skip"))));
            return;
        }
        String previous = fields[4];
        if (!List.of("yes", "no", "skip").contains(previous)) {
            throw new IllegalArgumentException("Неизвестный ответ об исходнике.");
        }
        store.vote(id, chat, result, FORMATS.get(format), previous);
        showSaved(chat, messageId, id, result);
    }

    private void showSaved(long chat, long message, String id, String result) {
        if (result == null) {
            client.editButtons(chat, message, resultButton(id));
            return;
        }
        var buttons = new java.util.ArrayList<Map<String, String>>();
        buttons.add(BotScreens.button("✏️ " + RESULTS.get(result) + " · изменить", "fb:" + id));
        if (config.feedbackUploadsEnabled()) {
            buttons.add(BotScreens.button("📎 Приложить материалы", "mat:f" + id + ":start"));
        }
        client.editButtons(chat, message, keys(buttons));
    }
}
