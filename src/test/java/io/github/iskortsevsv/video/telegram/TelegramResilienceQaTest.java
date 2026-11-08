package io.github.iskortsevsv.video.telegram;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.github.iskortsevsv.video.config.TelegramProperties;
import io.github.iskortsevsv.video.config.VideoProperties;
import io.github.iskortsevsv.video.service.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.json.JsonMapper;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

@org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(
        named = "RUN_FFMPEG_TESTS",
        matches = "true")
/** Проверяет обработку медиа и восстановление при сбоях на локальном имитаторе Telegram. */
class TelegramResilienceQaTest {
    static final String TOKEN = "123456789:TEST_ONLY_FAKE_TOKEN_12345678901234567890";
    static final long CHAT = 42;
    @TempDir Path directory;
    FakeTelegram fake;
    TestBot bot;

    @BeforeEach
    void start() throws Exception {
        fake = new FakeTelegram();
    }

    @AfterEach
    void stop() {
        if (bot != null) bot.close();
        fake.close();
    }

    TelegramProperties config(Set<Long> allowed) {
        return new TelegramProperties(true, TOKEN, directory.resolve("state/offset.txt"), allowed);
    }

    void launch(Set<Long> allowed) throws Exception {
        bot = new TestBot(config(allowed), fake.url());
        bot.start();
    }

    byte[] sample(String name) throws IOException {
        Path file = directory.resolve(name);
        boolean longVideo = name.equals("concurrency.mp4");
        String ffmpeg = System.getenv().getOrDefault("FFMPEG_PATH", "ffmpeg");
        var command =
                new ArrayList<>(
                        List.of(
                                ffmpeg,
                                "-hide_banner",
                                "-loglevel",
                                "error",
                                "-y",
                                "-f",
                                "lavfi",
                                "-i",
                                longVideo
                                        ? "testsrc2=size=1280x720:rate=15:duration=12"
                                        : "testsrc2=size=240x320:rate=15:duration=2"));
        if (!longVideo)
            command.addAll(List.of("-f", "lavfi", "-i", "sine=frequency=440:duration=2"));
        command.addAll(List.of("-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p"));
        if (!longVideo) command.addAll(List.of("-c:a", "aac"));
        command.add(file.toString());
        try {
            var process =
                    new ProcessBuilder(command)
                            .redirectErrorStream(true)
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            .start();
            if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0)
                throw new IOException("Cannot generate test video");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException(ex);
        }
        return Files.readAllBytes(file);
    }

    void update(long updateId, long chatId, String filename, String caption, long size) {
        String payload =
                "{\"update_id\":"
                        + updateId
                        + ",\"message\":{\"message_id\":5,\"chat\":{\"id\":"
                        + chatId
                        + ",\"type\":\"private\",\"first_name\":\"Test\"},\"document\":{\"file_id\":\"sample\",\"file_name\":\""
                        + filename
                        + "\",\"file_size\":"
                        + size
                        + "},\"caption\":\""
                        + caption
                        + "\"}}";
        fake.updates.add(new Update(updateId, payload));
    }

    void command(long id, String text) {
        fake.updates.add(
                new Update(
                        id,
                        "{\"update_id\":"
                                + id
                                + ",\"message\":{\"message_id\":5,\"chat\":{\"id\":42,\"type\":\"private\",\"first_name\":\"Test\"},\"text\":\""
                                + text
                                + "\"}}"));
    }

    void callback(long id, String data) {
        fake.updates.add(
                new Update(
                        id,
                        "{\"update_id\":"
                                + id
                                + ",\"callback_query\":{\"id\":\"cb\",\"from\":{\"id\":42},\"data\":\""
                                + data
                                + "\",\"message\":{\"message_id\":5,\"chat\":{\"id\":42,\"type\":\"private\",\"first_name\":\"Test\"}}}}"));
    }

    static void await(java.util.function.BooleanSupplier condition, int seconds) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(40);
        assertTrue(condition.getAsBoolean(), "Timed out waiting for fake Telegram event");
    }

    static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @Test
    void analyzesSourceOnceForThreeDeliveredCopies() throws Exception {
        fake.media = sample("audio.mp4");
        update(100, CHAT, "input.mp4", "", fake.media.length);
        launch(Set.of(CHAT));
        await(() -> fake.messages.stream().anyMatch(s -> s.contains("Все копии отправлены")), 25);
        assertEquals(3, fake.uploads.size());
        assertEquals(1, bot.probes.get());
    }

    @Test
    void uncertainDeliveryCanReportFailureAfterRemoteAcceptedBytes() throws Exception {
        fake.media = sample("audio.mp4");
        fake.dropUploadReply.set(true);
        update(100, CHAT, "input.mp4", "", fake.media.length);
        launch(Set.of(CHAT));
        await(() -> fake.acceptedButUnanswered.get() > 0, 20);
        await(() -> fake.messages.stream().anyMatch(s -> s.contains("подтвердить доставку")), 10);
        assertEquals(
                1,
                fake.acceptedButUnanswered.get(),
                "Uncertain send must not be retried automatically");
        assertTrue(
                fake.acceptedButUnanswered.get() >= 1,
                "Fake received uploaded media before losing response");
        assertEquals("101", Files.readString(config(Set.of(CHAT)).offsetFile()).strip());
    }

    @Test
    void videoUploadRateLimitRetriesAndDelivers() throws Exception {
        fake.media = sample("audio.mp4");
        fake.failUploads.set(true);
        update(100, CHAT, "input.mp4", "", fake.media.length);
        launch(Set.of(CHAT));
        await(() -> fake.uploadAttempts.get() >= 1, 20);
        await(() -> fake.uploads.size() == 3, 25);
        assertEquals(4, fake.uploadAttempts.get());
        assertTrue(
                fake.uploadTimes.get(1) - fake.uploadTimes.get(0)
                        >= TimeUnit.MILLISECONDS.toNanos(4900),
                "Retry must honor Telegram retry_after=5");
        assertEquals("101", Files.readString(config(Set.of(CHAT)).offsetFile()).strip());
    }

    @Test
    void forcedKillAfterAcknowledgementResumesJob() throws Exception {
        fake.media = sample("audio.mp4");
        fake.blockFile.set(true);
        update(100, CHAT, "input.mp4", "", fake.media.length);
        Path offset = config(Set.of(CHAT)).offsetFile();
        Path temp = Files.createDirectory(directory.resolve("isolated-temp"));
        Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        Process child =
                new ProcessBuilder(
                                java.toString(),
                                "-Djava.io.tmpdir=" + temp,
                                "-cp",
                                System.getProperty("java.class.path"),
                                CrashBot.class.getName(),
                                fake.url(),
                                offset.toString())
                        .redirectErrorStream(true)
                        .redirectOutput(directory.resolve("child.log").toFile())
                        .start();
        try {
            assertTrue(fake.fileEntered.await(20, TimeUnit.SECONDS), "Job never reached download");
            assertEquals("101", Files.readString(offset).strip(), "Offset saved before delivery");
            assertTrue(fake.messages.stream().anyMatch(s -> s.contains("Файл принят")));
            child.destroyForcibly();
            assertTrue(child.waitFor(10, TimeUnit.SECONDS));
            fake.releaseFile.countDown();
            fake.blockFile.set(false);
            launch(Set.of(CHAT));
            await(() -> fake.offsets.stream().anyMatch(n -> n >= 101), 10);
            await(() -> fake.uploads.size() == 3, 30);
            assertTrue(fake.fileCalls.get() >= 2, "Job must be downloaded again on restart");
            String log = Files.readString(directory.resolve("child.log"));
            assertFalse(log.contains(TOKEN));
        } finally {
            fake.releaseFile.countDown();
            child.destroyForcibly();
            child.waitFor();
        }
    }

    @Test
    void crashDuringSecondUploadDoesNotResendUncertainCopy() throws Exception {
        fake.media = sample("audio.mp4");
        fake.blockUploadAt.set(2);
        update(100, CHAT, "input.mp4", "", fake.media.length);
        Path offset = config(Set.of(CHAT)).offsetFile();
        Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        Process child =
                new ProcessBuilder(
                                java.toString(),
                                "-cp",
                                System.getProperty("java.class.path"),
                                CrashBot.class.getName(),
                                fake.url(),
                                offset.toString())
                        .redirectErrorStream(true)
                        .redirectOutput(directory.resolve("second-upload.log").toFile())
                        .start();
        try {
            assertTrue(fake.uploadEntered.await(30, TimeUnit.SECONDS));
            assertEquals(1, fake.uploads.size());
            child.destroyForcibly();
            assertTrue(child.waitFor(10, TimeUnit.SECONDS));
            fake.releaseUpload.countDown();
            launch(Set.of(CHAT));
            await(
                    () ->
                            fake.messages.stream()
                                    .anyMatch(s -> s.contains("неизвестно, была ли отправлена")),
                    15);
            Thread.sleep(500);
            assertEquals(2, fake.uploadAttempts.get(), "An uncertain upload must not be repeated");
        } finally {
            fake.releaseUpload.countDown();
            child.destroyForcibly();
            child.waitFor();
        }
    }

    @Test
    void forcedKillDuringFfmpegMayLeaveChildProcessRunning() throws Exception {
        fake.media = sample("concurrency.mp4");
        update(100, CHAT, "input.mp4", "", fake.media.length);
        Path offset = config(Set.of(CHAT)).offsetFile();
        Path temp = Files.createDirectory(directory.resolve("process-temp"));
        Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        Process child =
                new ProcessBuilder(
                                java.toString(),
                                "-Djava.io.tmpdir=" + temp,
                                "-cp",
                                System.getProperty("java.class.path"),
                                CrashBot.class.getName(),
                                fake.url(),
                                offset.toString())
                        .redirectErrorStream(true)
                        .redirectOutput(directory.resolve("process-child.log").toFile())
                        .start();
        ProcessHandle ffmpeg = null;
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (System.nanoTime() < deadline && child.isAlive() && ffmpeg == null) {
                ffmpeg =
                        child.descendants()
                                .filter(
                                        p ->
                                                p.info()
                                                        .command()
                                                        .orElse("")
                                                        .toLowerCase(Locale.ROOT)
                                                        .endsWith("ffmpeg.exe"))
                                .findFirst()
                                .orElse(null);
                if (ffmpeg == null) Thread.sleep(10);
            }
            assertNotNull(ffmpeg, "Did not observe active FFmpeg child");
            child.destroyForcibly();
            assertTrue(child.waitFor(10, TimeUnit.SECONDS));
            Thread.sleep(200);
            boolean orphanAlive = ffmpeg.isAlive();
            System.out.println("FAULT_QA_FFMPEG_SURVIVED_PARENT=" + orphanAlive);
            assertFalse(orphanAlive, "FFmpeg survived its parent");
            assertEquals("101", Files.readString(offset).strip());
            var constructor = WorkspaceCleaner.class.getDeclaredConstructor(Path.class);
            constructor.setAccessible(true);
            var cleaner = constructor.newInstance(temp);
            var sweep = WorkspaceCleaner.class.getDeclaredMethod("sweep");
            sweep.setAccessible(true);
            sweep.invoke(cleaner);
            try (var paths = Files.newDirectoryStream(temp, "vuniq-managed-*")) {
                int remaining = 0;
                for (Path ignored : paths) remaining++;
                System.out.println("FAULT_QA_WORKSPACES_AFTER_RESTART_SCAN=" + remaining);
            }
            if (ffmpeg.isAlive()) {
                ffmpeg.destroyForcibly();
                ffmpeg.onExit().get(10, TimeUnit.SECONDS);
            }
            sweep.invoke(cleaner);
            try (var paths = Files.newDirectoryStream(temp, "vuniq-managed-*")) {
                assertFalse(
                        paths.iterator().hasNext(),
                        "Cleanup after stopping FFmpeg should remove the workspace");
            }
        } finally {
            if (ffmpeg != null && ffmpeg.isAlive()) {
                ffmpeg.destroyForcibly();
                ffmpeg.onExit().get(10, TimeUnit.SECONDS);
            }
            child.destroyForcibly();
            child.waitFor();
        }
    }

    /** Запускает тестовый экземпляр бота в отдельном процессе для аварийных сценариев. */
    public static class CrashBot {
        public static void main(String[] args) throws Exception {
            var config = new TelegramProperties(true, TOKEN, Path.of(args[1]), Set.of(CHAT));
            var bot = new TestBot(config, args[0]);
            bot.start();
            Thread.sleep(60000);
        }
    }

    /** Собирает изолированный экземпляр бота для интеграционных проверок. */
    static class TestBot implements AutoCloseable {
        final AtomicInteger probes = new AtomicInteger();
        final TelegramPoller poller;
        final BotJobs jobs;

        TestBot(TelegramProperties config, String url) {
            var client = new TelegramClient(config, url);
            var props =
                    new VideoProperties(
                            System.getenv("FFMPEG_PATH"),
                            System.getenv("FFPROBE_PATH"),
                            Duration.ofSeconds(20),
                            2);
            var video = new VideoProcessor(new ProcessRunner() {
                @Override
                public String run(List<String> command, Duration timeout) {
                    if (command.getFirst().equals(props.ffprobe())) {probes.incrementAndGet();}
                    return super.run(command, timeout);
                }
            }, props);
            var hashes = new HashService();
            var users = new BotUserStore(config);
            jobs = new BotJobs();
            var service = new VideoService(video, hashes, props);
            var batch =
                    new MediaBatchService(
                            client, service, video, new PhotoProcessor(), hashes, users);
            poller =
                    new TelegramPoller(
                            client,
                            new TelegramHandler(client, config, users, jobs, batch),
                            config, jobs);
        }

        void start() throws Exception {
            poller.start();
        }

        public void close() {
            poller.stop();
            jobs.stop();
        }
    }

    /** Представляет тестовое обновление Telegram и его идентификатор. */
    record Update(long id, String json) {}

    /** Имитирует нужные методы Telegram API для интеграционных тестов. */
    static class FakeTelegram implements AutoCloseable {
        final HttpServer server;
        final ExecutorService executor = Executors.newCachedThreadPool();
        final JsonMapper mapper = new JsonMapper();
        final List<Update> updates = new CopyOnWriteArrayList<>();
        final List<Long> offsets = new CopyOnWriteArrayList<>();
        final List<Long> pollTimes = new CopyOnWriteArrayList<>();
        final List<Long> uploadTimes = new CopyOnWriteArrayList<>();
        final List<String> messages = new CopyOnWriteArrayList<>();
        final List<byte[]> uploads = new CopyOnWriteArrayList<>();
        final AtomicInteger uploadAttempts = new AtomicInteger(),
                fileCalls = new AtomicInteger(),
                sequence = new AtomicInteger();
        final AtomicBoolean rateLimitFirst = new AtomicBoolean(),
                failUploads = new AtomicBoolean(),
                blockFile = new AtomicBoolean(),
                failFile = new AtomicBoolean(),
                dropUploadReply = new AtomicBoolean(),
                stallPolling = new AtomicBoolean(),
                conflictPolling = new AtomicBoolean();
        final AtomicInteger acceptedButUnanswered = new AtomicInteger();
        final AtomicInteger blockUploadAt = new AtomicInteger();
        final CountDownLatch uploadEntered = new CountDownLatch(1),
                releaseUpload = new CountDownLatch(1);
        final CountDownLatch fileEntered = new CountDownLatch(1),
                releaseFile = new CountDownLatch(1);
        volatile byte[] media = new byte[0];

        FakeTelegram() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/", this::handle);
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            byte[] request = exchange.getRequestBody().readAllBytes();
            try {
                if (path.endsWith("/getUpdates")) {
                    pollTimes.add(System.nanoTime());
                    if (stallPolling.get()) {
                        Thread.sleep(65000);
                        exchange.close();
                        return;
                    }
                    if (conflictPolling.get()) {
                        respond(
                                exchange,
                                409,
                                "{\"ok\":false,\"error_code\":409,\"description\":\"Conflict:"
                                        + " another poller\"}");
                        return;
                    }
                    if (rateLimitFirst.compareAndSet(true, false)) {
                        respond(exchange, 429, "{\"ok\":false,\"parameters\":{\"retry_after\":1}}");
                        return;
                    }
                    long offset = mapper.readTree(request).path("offset").asLong();
                    offsets.add(offset);
                    var update = updates.stream().filter(item -> item.id >= offset).findFirst();
                    if (update.isEmpty()) Thread.sleep(60);
                    respond(
                            exchange,
                            200,
                            "{\"ok\":true,\"result\":["
                                    + update.map(Update::json).orElse("")
                                    + "]}");
                } else if (path.endsWith("/getFile")) {
                    fileCalls.incrementAndGet();
                    fileEntered.countDown();
                    if (failFile.get()) {
                        exchange.close();
                        return;
                    }
                    if (blockFile.get()) releaseFile.await(30, TimeUnit.SECONDS);
                    respond(
                            exchange,
                            200,
                            "{\"ok\":true,\"result\":{\"file_path\":\"videos/sample.mp4\",\"file_size\":"
                                    + media.length
                                    + "}}");
                } else if (path.contains("/file/bot")) {
                    exchange.sendResponseHeaders(200, media.length);
                    exchange.getResponseBody().write(media);
                    exchange.close();
                } else if (path.endsWith("/sendVideo") || path.endsWith("/sendDocument")) {
                    uploadTimes.add(System.nanoTime());
                    int attempt = uploadAttempts.incrementAndGet();
                    if (attempt == blockUploadAt.get()) {
                        uploadEntered.countDown();
                        releaseUpload.await(30, TimeUnit.SECONDS);
                        exchange.close();
                        return;
                    }
                    if (dropUploadReply.get()) {
                        acceptedButUnanswered.incrementAndGet();
                        exchange.close();
                        return;
                    }
                    if (failUploads.getAndSet(false)) {
                        respond(exchange, 429, "{\"ok\":false,\"parameters\":{\"retry_after\":5}}");
                        return;
                    }
                    uploads.add(request);
                    respond(exchange, 200, "{\"ok\":true,\"result\":{\"message_id\":90}}");
                } else if (path.endsWith("/sendMessage")) {
                    messages.add(mapper.readTree(request).path("text").asText(""));
                    respond(
                            exchange,
                            200,
                            "{\"ok\":true,\"result\":{\"message_id\":"
                                    + sequence.incrementAndGet()
                                    + "}}");
                } else {
                    respond(exchange, 200, "{\"ok\":true,\"result\":true}");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                exchange.close();
            }
        }

        void respond(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        public void close() {
            releaseFile.countDown();
            releaseUpload.countDown();
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
