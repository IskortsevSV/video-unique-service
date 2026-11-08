package io.github.iskortsevsv.video.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.iskortsevsv.video.config.TelegramProperties;

/** Проверяет взаимодействие с Telegram API через локальный HTTP-сервер. */
class TelegramClientTest {
    @TempDir Path directory;
    HttpServer server;
    TelegramClient client;
    AtomicReference<String> request = new AtomicReference<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        client =
                new TelegramClient(
                        new TelegramProperties(
                                true, "123:fake", directory.resolve("offset"), Set.of()),
                        "http://127.0.0.1:" + server.getAddress().getPort());
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    void response(String path, int status, String response) {
        server.createContext(
                path,
                exchange -> {
                    request.set(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
    }

    @Test
    void pollsUsingOffsetAndUploadsMultipartDocument() throws Exception {
        response("/bot123:fake/getUpdates", 200, "{\"ok\":true,\"result\":[{\"update_id\":7}]}");
        assertEquals(7, client.updates(7).get(0).path("update_id").asInt());
        assertTrue(request.get().contains("\"offset\":7"));
        response("/bot123:fake/sendDocument", 200, "{\"ok\":true,\"result\":{}}");
        client.document(
                42, Files.writeString(directory.resolve("output.mp4"), "VIDEO-BYTES"), "hashes");
        assertTrue(request.get().contains("VIDEO-BYTES"));
        assertTrue(request.get().contains("name=\"document\""));
        assertTrue(request.get().contains("hashes"));
    }

    @Test
    void downloadsAndHonorsRateLimitsWithoutLeakingToken() throws Exception {
        response(
                "/bot123:fake/getFile",
                200,
                "{\"ok\":true,\"result\":{\"file_path\":\"videos/x.mp4\",\"file_size\":3}}");
        response("/file/bot123:fake/videos/x.mp4", 200, "abc");
        Path output = directory.resolve("input.mp4");
        client.download("file-id", output);
        assertEquals("abc", Files.readString(output));
        response(
                "/bot123:fake/sendMessage",
                429,
                "{\"ok\":false,\"parameters\":{\"retry_after\":30}}");
        var error = assertThrows(TelegramApiException.class, () -> client.message(42, "hello"));
        assertEquals(30, error.retrySeconds());
        assertFalse(error.toString().contains("123:fake"));
        assertNull(error.getCause());
    }

    @Test
    void rejectsOversizedDownloadMetadata() {
        response(
                "/bot123:fake/getFile",
                200,
                "{\"ok\":true,\"result\":{\"file_path\":\"videos/x.mp4\",\"file_size\":20000001}}");
        assertThrows(
                IllegalArgumentException.class,
                () -> client.download("file-id", directory.resolve("input.mp4")));
    }

    @Test
    void menuCallbacksAndVideoUploadUseTelegramMethods() throws Exception {
        response("/bot123:fake/sendMessage", 200, "{\"ok\":true,\"result\":{\"message_id\":99}}");
        assertEquals(99, client.screen(42, "<b>Menu</b>", BotScreens.homeKeys()));
        assertTrue(request.get().contains("inline_keyboard"));
        response("/bot123:fake/answerCallbackQuery", 200, "{\"ok\":true,\"result\":true}");
        client.answer("callback");
        assertTrue(request.get().contains("callback_query_id"));
        response("/bot123:fake/editMessageText", 200, "{\"ok\":true,\"result\":{}}");
        client.edit(42, 99, "progress", BotScreens.homeKeys());
        assertTrue(request.get().contains("\"message_id\":99"));
        response("/bot123:fake/sendVideo", 200, "{\"ok\":true,\"result\":{}}");
        client.result(
                42, 8, Files.writeString(directory.resolve("copy.mp4"), "MP4"), "caption", false);
        assertTrue(request.get().contains("name=\"video\""));
        assertTrue(request.get().contains("supports_streaming"));
    }
}
