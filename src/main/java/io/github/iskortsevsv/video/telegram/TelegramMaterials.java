package io.github.iskortsevsv.video.telegram;

import io.github.iskortsevsv.video.config.TelegramProperties;
import io.github.iskortsevsv.video.service.TemporaryWorkspace;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/** Принимает материалы только в ответ на запрос конкретного файла после согласия. */
@Component
@ConditionalOnProperty(name = "telegram.enabled", havingValue = "true")
class TelegramMaterials {
    private static final String PROMPT = "📎 VUniq · ";
    private final TelegramClient client;
    private final TelegramProperties config;
    private final FeedbackStore feedback;
    private final MaterialArchive archive;
    private final BotJobs jobs;
    private final Path prompts;

    TelegramMaterials(TelegramClient client, TelegramProperties config, FeedbackStore feedback,
            MaterialArchive archive, BotJobs jobs) {
        this.client = client;
        this.config = config;
        this.feedback = feedback;
        this.archive = archive;
        this.jobs = jobs;
        prompts = config.offsetFile().toAbsolutePath().resolveSibling("material-prompts");
    }

    boolean handle(long chat, JsonNode message, String action, boolean callback) throws IOException {
        var reply = message.path("reply_to_message");
        boolean materialReply = !callback && !action.startsWith("/")
                && reply.path("from").path("is_bot").asBoolean(false)
                && reply.path("text").asText("").startsWith(PROMPT);
        boolean reference = !callback && "/reference".equals(action);
        boolean materialButton = callback && action.startsWith("mat:");
        if (!materialReply && !reference && !materialButton) {
            return false;
        }
        if (!config.feedbackUploadsEnabled() && !config.referenceEnabled()) {
            client.message(chat, "Приём материалов на этом экземпляре выключен.");
            return true;
        }
        if (reference) {
            if (canUseMaterials(chat, true)) {
                consent(chat, "r" + UUID.randomUUID().toString().replace("-", ""), true);
            }
        } else if (materialReply) {
            acceptReply(chat, message, reply);
        } else {
            if (!message.path("from").path("is_bot").asBoolean(false)) {
                throw new IllegalArgumentException("Кнопка недействительна.");
            }
            handleButton(chat, action);
        }
        return true;
    }

    private boolean canUseMaterials(long chat, boolean reference) {
        if (!(reference ? config.referenceEnabled() : config.feedbackUploadsEnabled())) {
            client.message(chat, "Приём материалов для этого действия выключен.");
            return false;
        }
        if (reference && !config.feedbackAdminIds().contains(chat)) {
            client.message(chat, "Команда /reference доступна администратору.");
            return false;
        }
        return true;
    }

    private void handleButton(long chat, String action) throws IOException {
        String[] fields = action.split(":", -1);
        if (fields.length != 3 || !fields[1].matches("[fr][a-f0-9]{32}")) {
            throw new IllegalArgumentException("Это обращение недоступно.");
        }
        String id = fields[1];
        boolean reference = id.startsWith("r");
        if (!canUseMaterials(chat, reference)) {
            return;
        }
        String operation = fields[2];
        if ("start".equals(operation) || "consent".equals(operation)) {
            if (!reference && feedback.owned(id.substring(1), chat).getProperty("result") == null) {
                throw new IllegalArgumentException("Сначала отметьте результат публикации.");
            }
            if ("start".equals(operation) && !reference) {
                consent(chat, id, false);
            } else if ("consent".equals(operation)) {
                archive.open(id, chat);
                menu(chat, id, reference ? "Сначала приложите обработанный файл. Исходник можно добавить позже."
                        : "Выберите, какой материал приложить к отзыву.");
            }
            return;
        }
        archive.requireOwner(id, chat);
        if ("delete".equals(operation)) {
            archive.delete(id, chat);
            Path path = promptPath(chat);
            if (id.equals(TelegramStateFiles.read(path).getProperty("case"))) {
                java.nio.file.Files.deleteIfExists(path);
            }
            client.message(chat, "Материалы обращения удалены.");
            return;
        }
        var role = MaterialArchive.Role.valueOf(operation);
        if (reference && role == MaterialArchive.Role.SCREENSHOT) {
            throw new IllegalArgumentException("Для сравнения загрузите исходник или обработанную копию.");
        }
        long promptId = client.filePrompt(chat, PROMPT + role.label
                + ". Отправьте один файл ответом на это сообщение.");
        if (promptId <= 0) {
            throw new IOException("Missing Telegram prompt message ID");
        }
        var prompt = new Properties();
        prompt.setProperty("case", id);
        prompt.setProperty("role", role.name());
        prompt.setProperty("message", Long.toString(promptId));
        prompt.setProperty("created", Instant.now().toString());
        TelegramStateFiles.write(promptPath(chat), prompt);
    }

    private void acceptReply(long chat, JsonNode message, JsonNode reply) throws IOException {
        var prompt = TelegramStateFiles.read(promptPath(chat));
        String promptId = reply.path("message_id").asText("");
        if (!promptId.equals(prompt.getProperty("message"))
                || Instant.parse(prompt.getProperty("created")).plusSeconds(86_400).isBefore(Instant.now())) {
            throw new IllegalArgumentException("Запрос устарел. Откройте обращение и выберите роль файла снова.");
        }
        String id = prompt.getProperty("case");
        if (!canUseMaterials(chat, id.startsWith("r"))) {
            return;
        }
        archive.requireOwner(id, chat);
        var role = MaterialArchive.Role.valueOf(prompt.getProperty("role"));
        JsonNode media = message.path("document");
        if (message.has("video")) {
            media = message.path("video");
        } else if (message.path("photo").size() > 0) {
            var photos = message.path("photo");
            media = photos.get(photos.size() - 1);
        }
        String fileId = media.path("file_id").asText("");
        if (fileId.isBlank()) {
            throw new IllegalArgumentException("Отправьте JPEG, PNG, MP4 или MOV.");
        }
        config.checkInputSize(media.path("file_size").asLong(0));
        if (!jobs.submit(chat, () -> save(chat, id, role, fileId, promptId))) {
            client.message(chat, "Очередь занята. Повторите ответ на тот же запрос позже.");
        }
    }

    private void save(long chat, String id, MaterialArchive.Role role, String fileId, String promptId) {
        String summary;
        try (var workspace = new TemporaryWorkspace()) {
            Path incoming = workspace.file("material");
            client.download(fileId, incoming);
            if (!promptId.equals(TelegramStateFiles.read(promptPath(chat)).getProperty("message"))) {
                return;
            }
            summary = archive.add(id, chat, role, incoming, Instant.now());
        } catch (IllegalArgumentException ex) {
            client.message(chat, ex.getMessage());
            return;
        } catch (IOException ex) {
            client.message(chat, "Не удалось сохранить материал. Попробуйте ещё раз.");
            return;
        }
        menu(chat, id, summary);
    }

    private void consent(long chat, String id, boolean reference) {
        client.screen(chat, (reference ? "Пример другого сервиса" : "Материалы к отзыву")
                + "\n\nПрисылайте только файлы, которыми имеете право делиться. "
                + "Материалы сохраняются для разбора на 7 дней. Их можно удалить раньше.",
                TelegramFeedback.keys(List.of(
                        BotScreens.button("Права есть, согласен на хранение", "mat:" + id + ":consent"))));
    }

    private void menu(long chat, String id, String text) {
        var buttons = new ArrayList<Map<String, String>>();
        if (id.startsWith("f")) {
            buttons.add(roleButton(id, MaterialArchive.Role.SCREENSHOT));
        }
        buttons.add(roleButton(id, MaterialArchive.Role.COPY));
        buttons.add(roleButton(id, MaterialArchive.Role.SOURCE));
        buttons.add(BotScreens.button("🗑 Удалить материалы", "mat:" + id + ":delete"));
        client.screen(chat, BotScreens.escape(text), TelegramFeedback.keys(buttons));
    }

    private static Map<String, String> roleButton(String id, MaterialArchive.Role role) {
        return BotScreens.button("📎 " + role.label, "mat:" + id + ":" + role.name());
    }

    private Path promptPath(long chat) {
        return prompts.resolve(chat + ".properties");
    }
}
