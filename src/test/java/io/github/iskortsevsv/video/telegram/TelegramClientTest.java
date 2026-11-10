package io.github.iskortsevsv.video.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.github.iskortsevsv.video.config.TelegramProperties;
import tools.jackson.databind.json.JsonMapper;

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
    void localApiMovesA150MbDownloadAndSendsTheResultByPath() throws Exception {
        Path cache = Files.createDirectory(directory.resolve("media"));
        Path source = cache.resolve("video.mp4");
        try (var file = new RandomAccessFile(source.toFile(), "rw")) {
            file.setLength(150_000_000);
        }
        var local = localClient(cache, source);
        Path output = directory.resolve("processed.mp4");
        local.download("file-id", output);
        assertEquals(150_000_000, Files.size(output));
        assertFalse(Files.exists(source));

        response("/bot123:fake/sendVideo", 200, "{\"ok\":true,\"result\":{}}");
        local.result(42, 8, output, "caption", false);
        assertTrue(request.get().contains(output.toUri().toASCIIString()));
    }

    @Test
    void localApiRejectsLargerFilesEvenWhenMetadataOmitsTheSize() throws Exception {
        Path cache = Files.createDirectory(directory.resolve("media"));
        Path source = cache.resolve("oversized.mp4");
        try (var file = new RandomAccessFile(source.toFile(), "rw")) {
            file.setLength(150_000_001);
        }
        var local = localClient(cache, source);
        Path workspace = Files.createDirectory(directory.resolve("workspace"));
        var error = assertThrows(
                IllegalArgumentException.class,
                () -> local.download("file-id", workspace.resolve("input.mp4")));
        assertTrue(error.getMessage().contains("150 МБ"));
        assertFalse(Files.exists(source));
    }

    @Test
    void localApiRejectsFilesOutsideItsMediaDirectory() throws Exception {
        Path cache = Files.createDirectory(directory.resolve("media"));
        Path outside = Files.writeString(directory.resolve("outside.mp4"), "outside");
        var local = localClient(cache, outside);
        Path destination = directory.resolve("input.mp4");
        assertThrows(IllegalArgumentException.class, () -> local.download("file-id", destination));
        assertTrue(Files.isRegularFile(outside));
        assertFalse(Files.exists(destination));
    }

    private TelegramClient localClient(Path cache, Path source) {
        var config = new TelegramProperties(
                true, "123:fake", directory.resolve("offset"), Set.of(), server.getAddress().getPort(), cache);
        response("/bot123:fake/getFile", 200, new JsonMapper().writeValueAsString(
                Map.of("ok", true, "result", Map.of("file_path", source.toString()))));
        return new TelegramClient(config);
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

    @ParameterizedTest
    @CsvSource({"400,true,false", "403,true,false", "404,true,false", "401,false,true",
            "409,false,true", "429,false,true", "500,false,false", "503,false,false"})
    void classifiesErrorsWithoutKeepingResponseBody(int code, boolean permanent, boolean global) {
        response("/bot123:fake/sendMessage", code,
                "{\"ok\":false,\"error_code\":" + code
                        + ",\"description\":\"secret 123:fake\",\"parameters\":{\"retry_after\":7}}");
        var error = assertThrows(TelegramApiException.class, () -> client.message(42, "hello"));
        assertEquals(code, error.errorCode());
        assertEquals(permanent, error.permanent());
        assertEquals(global, error.global());
        // HttpURLConnection can suppress the response body for an authentication challenge.
        assertEquals(code == 401 ? 5 : 7, error.retrySeconds());
        assertEquals(code == 429, error.rateLimited());
        assertFalse(error.toString().contains("123:fake"));
        assertNull(error.getCause());
    }

    @Test
    void keepsErrorCodeFromTelegramEnvelope() {
        response("/bot123:fake/sendMessage", 200, "{\"ok\":false,\"error_code\":403}");
        assertTrue(assertThrows(TelegramApiException.class,
                () -> client.message(42, "hello")).permanent());
    }

    @Test
    void keepsHttpFailureWhenResponseIsNotJson() {
        response("/bot123:fake/sendMessage", 403, "<html>123:fake forbidden</html>");
        var error = assertThrows(TelegramApiException.class, () -> client.message(42, "hello"));
        assertEquals(403, error.errorCode());
        assertTrue(error.permanent());
        assertNull(error.getCause());
        assertFalse(error.toString().contains("123:fake"));
    }

    @Test
    void expiredCallbackDoesNotPreventItsAction() throws Exception {
        response("/bot123:fake/answerCallbackQuery", 400,
                "{\"ok\":false,\"error_code\":400,\"description\":\"query is too old\"}");
        response("/bot123:fake/editMessageText", 200, "{\"ok\":true,\"result\":{}}");
        var config = new TelegramProperties(true, "123:fake", directory.resolve("offset"), Set.of());
        var jobs = new BotJobs();
        try {
            var handler = new TelegramHandler(client, config, new BotUserStore(config), jobs, null);
            var update = new tools.jackson.databind.json.JsonMapper().readTree("""
                    {"callback_query":{"id":"expired","from":{"id":42},"data":"copies:5",
                    "message":{"message_id":10,"chat":{"id":42,"type":"private"}}}}
                    """);
            assertDoesNotThrow(() -> handler.handle(update));
            assertEquals(5, new BotUserStore(config).get(42).copies());
            assertTrue(request.get().contains("message_id"));
        } finally {
            jobs.stop();
        }
    }

    @ParameterizedTest
    @CsvSource({"429", "500", "401"})
    void callbackDoesNotHideTemporaryOrGlobalFailure(int code) {
        response("/bot123:fake/answerCallbackQuery", code, "{\"ok\":false}");
        assertEquals(code, assertThrows(TelegramApiException.class,
                () -> client.answer("callback")).errorCode());
    }
}
