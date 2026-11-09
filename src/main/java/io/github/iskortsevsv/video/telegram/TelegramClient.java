package io.github.iskortsevsv.video.telegram;

import io.github.iskortsevsv.video.config.TelegramProperties;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/** Вызывает Telegram Bot API и ограничивает размер передаваемых файлов. */
@Component
@ConditionalOnProperty(name = "telegram.enabled", havingValue = "true")
public class TelegramClient {
    public static final long MAX_INPUT = 20_000_000;
    public static final long MAX_OUTPUT = 50_000_000;
    private final RestClient http;
    private final String api;
    private final String files;
    private final JsonMapper mapper = new JsonMapper();

    @org.springframework.beans.factory.annotation.Autowired
    public TelegramClient(TelegramProperties properties) {
        this(properties, "https://api.telegram.org");
    }

    TelegramClient(TelegramProperties properties, String base) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(60));
        http = RestClient.builder().requestFactory(factory).build();
        api = base + "/bot" + properties.token() + "/";
        files = base + "/file/bot" + properties.token() + "/";
    }

    /** Получает очередные обновления Telegram после заданной позиции. */
    public JsonNode updates(long offset) {
        return call(
                "getUpdates",
                Map.of(
                        "offset",
                        offset,
                        "timeout",
                        25,
                        "limit",
                        1,
                        "allowed_updates",
                        new String[] {"message", "callback_query"}));
    }

    /** Отправляет текстовое сообщение в чат. */
    public void message(long chat, String text) {
        call("sendMessage", Map.of("chat_id", chat, "text", text));
    }

    /** Отправляет HTML-сообщение с клавиатурой и возвращает его идентификатор. */
    public long screen(long chat, String text, Object keyboard) {
        return call(
                        "sendMessage",
                        Map.of(
                                "chat_id",
                                chat,
                                "text",
                                text,
                                "parse_mode",
                                "HTML",
                                "reply_markup",
                                keyboard))
                .path("message_id")
                .asLong();
    }

    /** Обновляет текст и клавиатуру ранее отправленного сообщения. */
    public void edit(long chat, long message, String text, Object keyboard) {
        call(
                "editMessageText",
                Map.of(
                        "chat_id",
                        chat,
                        "message_id",
                        message,
                        "text",
                        text,
                        "parse_mode",
                        "HTML",
                        "reply_markup",
                        keyboard));
    }

    /** Подтверждает обработку callback-запроса Telegram. */
    public void answer(String id) {
        try {
            call("answerCallbackQuery", Map.of("callback_query_id", id));
        } catch (TelegramApiException ex) {
            // An expired/already invalid callback acknowledgement must not block its action.
            if (ex.errorCode() != 400) {
                throw ex;
            }
        }
    }

    /** Регистрирует команды, меню и описание бота в Telegram. */
    public void configureMenu() {
        call(
                "setMyCommands",
                Map.of(
                        "commands",
                        java.util.List.of(
                                Map.of("command", "start", "description", "Главное меню"),
                                Map.of(
                                        "command",
                                        "settings",
                                        "description",
                                        "Режим и количество копий"),
                                Map.of(
                                        "command",
                                        "subscription",
                                        "description",
                                        "Подписка и статистика"),
                                Map.of(
                                        "command",
                                        "help",
                                        "description",
                                        "Как обработать фото или видео"),
                                Map.of("command", "id", "description", "Мой ID чата"))));
        call("setChatMenuButton", Map.of("menu_button", Map.of("type", "commands")));
        call(
                "setMyDescription",
                Map.of(
                        "description",
                        "VUniq — обработка фото и видео. Режимы Basic и Hard, до 10 копий, прогресс"
                            + " и SHA-256. Тестовый доступ бесплатный; оплата отключена. Различие"
                            + " файлов не гарантирует уникальность для соцсетей."));
    }

    /** Отправляет результат обработки с подписью в ответ на исходное сообщение. */
    public void result(long chat, long replyTo, Path output, String caption, boolean photo)
            throws IOException {
        if (Files.size(output) > MAX_OUTPUT) {
            throw new IllegalArgumentException("Результат превышает лимит Telegram 50 МБ.");
        }
        var body = new LinkedMultiValueMap<String, Object>();
        body.add("chat_id", Long.toString(chat));
        body.add("caption", caption);
        body.add("parse_mode", "HTML");
        body.add(
                "reply_parameters",
                mapper.writeValueAsString(
                        Map.of("message_id", replyTo, "allow_sending_without_reply", true)));
        // JPEG is returned as a document to preserve the exact hashed bytes. MP4 has an inline
        // player.
        body.add(photo ? "document" : "video", new FileSystemResource(output));
        if (!photo) {
            body.add("supports_streaming", "true");
        }
        try {
            http.post()
                    .uri(api + (photo ? "sendDocument" : "sendVideo"))
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .exchange(
                            (request, response) ->
                                    decode(
                                            response.getBody().readNBytes(1_048_576),
                                            response.getStatusCode().value()));
        } catch (TelegramApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new TelegramApiException(5);
        }
    }

    private JsonNode call(String method, Object body) {
        try {
            return http.post()
                    .uri(api + method)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange(
                            (request, response) ->
                                    decode(
                                            response.getBody().readNBytes(1_048_576),
                                            response.getStatusCode().value()));
        } catch (TelegramApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new TelegramApiException(5);
        }
    }

    private JsonNode decode(byte[] bytes, int status) {
        JsonNode result;
        try {
            result = mapper.readTree(bytes);
        } catch (RuntimeException ex) {
            // Preserve a known HTTP refusal even when a proxy returns a non-JSON error body.
            throw new TelegramApiException(5, status >= 400 ? status : 0);
        }
        if (status >= 400 || !result.path("ok").asBoolean()) {
            throw new TelegramApiException(
                    result.path("parameters").path("retry_after").asInt(5),
                    status >= 400 ? status : result.path("error_code").asInt(0));
        }
        return result.path("result");
    }

    /**
     * Скачивает файл Telegram, проверяя размер по метаданным и во время записи.
     *
     * @param fileId идентификатор файла в Telegram
     * @param destination путь для записи скачанного файла
     * @throws IllegalArgumentException если путь некорректен или файл превышает лимит
     * @throws TelegramApiException если Telegram недоступен или вернул ошибку
     */
    public void download(String fileId, Path destination) {
        var file = call("getFile", Map.of("file_id", fileId));
        String path = file.path("file_path").asText("");
        if (file.path("file_size").asLong(0) > MAX_INPUT
                || path.isBlank()
                || !path.matches("[A-Za-z0-9_./-]+")
                || path.contains("..")) {
            throw new IllegalArgumentException("Файл недоступен или превышает 20 МБ.");
        }
        try {
            http.get()
                    .uri(files + path)
                    .exchange(
                            (request, response) -> {
                                if (!response.getStatusCode().is2xxSuccessful()) {
                                    throw new TelegramApiException(5, response.getStatusCode().value());
                                }
                                try (var output = Files.newOutputStream(destination)) {
                                    byte[] buffer = new byte[8192];
                                    long total = 0;
                                    int count;
                                    while ((count = response.getBody().read(buffer)) != -1) {
                                        total += count;
                                        if (total > MAX_INPUT) {
                                            throw new IllegalArgumentException(
                                                    "Файл превышает 20 МБ.");
                                        }
                                        output.write(buffer, 0, count);
                                    }
                                }
                                return null;
                            });
        } catch (IllegalArgumentException | TelegramApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new TelegramApiException(5);
        }
    }

    /** Отправляет файл как документ с подписью. */
    public void document(long chat, Path output, String caption) throws IOException {
        if (Files.size(output) > MAX_OUTPUT) {
            throw new IllegalArgumentException("Результат превышает лимит Telegram 50 МБ.");
        }
        var body = new LinkedMultiValueMap<String, Object>();
        body.add("chat_id", Long.toString(chat));
        body.add("caption", caption);
        body.add("document", new FileSystemResource(output));
        try {
            http.post()
                    .uri(api + "sendDocument")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .exchange(
                            (request, response) ->
                                    decode(
                                            response.getBody().readNBytes(1_048_576),
                                            response.getStatusCode().value()));
        } catch (TelegramApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new TelegramApiException(5);
        }
    }
}
