package io.github.iskortsevsv.video.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.contains;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.github.iskortsevsv.video.config.TelegramProperties;
import io.github.iskortsevsv.video.config.VideoProperties;
import io.github.iskortsevsv.video.dto.ProcessingOptions;
import io.github.iskortsevsv.video.service.HashService;
import io.github.iskortsevsv.video.service.PhotoProcessor;
import io.github.iskortsevsv.video.service.VideoProcessor;
import io.github.iskortsevsv.video.service.VideoService;

/** Проверяет изоляцию отказов чатов, отложенные повторы и совместимость журнала. */
class TelegramFailureIsolationTest {
    @TempDir Path directory;
    private final JsonMapper json = new JsonMapper();

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void blockedChatDoesNotBlockNextChatIncludingAfterRecovery(boolean recovered) throws Exception {
        var config = config();
        var bad = message(100, 1, "/start");
        if (recovered) {
            new BotJournal(config.offsetFile()).stage(bad);
        }
        var client = mock(TelegramClient.class);
        var jobs = new BotJobs();
        var healthy = new CountDownLatch(1);
        var calls = new AtomicInteger();
        when(client.updates(anyLong())).thenAnswer(call -> {
            Thread.sleep(10);
            int number = calls.getAndIncrement();
            if (!recovered && number == 0) {
                return json.createArrayNode().add(bad);
            }
            return number == (recovered ? 0 : 1)
                    ? json.createArrayNode().add(message(101, 2, "/id")) : json.createArrayNode();
        });
        when(client.screen(eq(1L), anyString(), any())).thenThrow(new TelegramApiException(1, 403));
        doAnswer(call -> { healthy.countDown(); return null; })
                .when(client).message(eq(2L), anyString());
        var handler = new TelegramHandler(client, config, new BotUserStore(config), jobs, null);
        var poller = new TelegramPoller(client, handler, config, jobs);
        try {
            poller.start();
            assertTrue(healthy.await(3, TimeUnit.SECONDS));
            awaitOffset(config.offsetFile(), "102");
        } finally {
            poller.stop();
        }
        verify(client, times(1)).screen(eq(1L), anyString(), any());
        assertTrue(new BotJournal(config.offsetFile()).pending().isEmpty());
    }

    @Test
    void temporaryChatFailureAllowsNewUpdatesAndRetriesLater() throws Exception {
        var config = config();
        var client = mock(TelegramClient.class);
        var jobs = new BotJobs();
        var healthy = new CountDownLatch(1);
        var retried = new CountDownLatch(1);
        var attempts = new AtomicInteger();
        var polls = new AtomicInteger();
        when(client.updates(anyLong())).thenAnswer(call -> {
            Thread.sleep(10);
            return switch (polls.getAndIncrement()) {
                case 0 -> json.createArrayNode().add(message(100, 1, "/start"));
                case 1 -> json.createArrayNode().add(message(101, 2, "/id"));
                default -> json.createArrayNode();
            };
        });
        when(client.screen(eq(1L), anyString(), any())).thenAnswer(call -> {
            if (attempts.getAndIncrement() == 0) {
                throw new TelegramApiException(1, 503);
            }
            assertEquals(0, healthy.getCount(), "Other chat must run before retry");
            retried.countDown();
            return 7L;
        });
        doAnswer(call -> { healthy.countDown(); return null; })
                .when(client).message(eq(2L), anyString());
        var handler = new TelegramHandler(client, config, new BotUserStore(config), jobs, null);
        var poller = new TelegramPoller(client, handler, config, jobs);
        try {
            poller.start();
            assertTrue(healthy.await(3, TimeUnit.SECONDS));
            assertTrue(retried.await(4, TimeUnit.SECONDS));
            awaitOffset(config.offsetFile(), "102");
            awaitCompletion(directory.resolve("offset.jobs/100.properties"));
        } finally {
            poller.stop();
        }
        assertEquals(2, attempts.get());
        assertTrue(new BotJournal(config.offsetFile()).pending().isEmpty());
    }

    @Test
    void journalRetainsUncertainDeliveryAcrossDeferredRetryAndRestart() throws Exception {
        var journal = new BotJournal(config().offsetFile());
        var update = message(100, 1, "/start");
        var item = journal.stage(update);
        item.snapshot(new BotUserStore(config()).get(1), new ProcessingOptions(1.01, .98));
        item.delivered(1);
        item.sending(true);
        item.failed(new TelegramApiException(60, 503));
        var restarted = new BotJournal(config().offsetFile());
        assertTrue(restarted.pending().isEmpty());
        var restored = restarted.stage(update);
        assertFalse(restored.ready());
        assertFalse(restored.done());
        assertTrue(restored.uncertain());
        assertEquals(1, restored.sent());
        // Make the retry due without a timing-dependent wait.
        Path path = directory.resolve("offset.jobs/100.properties");
        var data = new Properties();
        try (var input = Files.newInputStream(path)) {
            data.load(input);
        }
        data.setProperty("retry-at", "0");
        try (var output = Files.newOutputStream(path)) {
            data.store(output, "test");
        }
        assertEquals(1, restarted.pending().size());
        assertTrue(restarted.stage(update).uncertain());
    }

    @Test
    void workerPersistsPermanentFailureAndReleasesChat() throws Exception {
        var journal = new BotJournal(config().offsetFile());
        var update = message(100, 1, "/start");
        var jobs = new BotJobs();
        var completed = new CountDownLatch(1);
        try {
            BotJournal.set(journal.stage(update));
            assertTrue(jobs.submit(1, () -> { throw new TelegramApiException(1, 403); }));
            BotJournal.set(null);
            // A queued action runs after the previous worker has saved state and released the chat.
            assertTrue(jobs.submit(2, completed::countDown));
            assertTrue(completed.await(3, TimeUnit.SECONDS));
            var restarted = new BotJournal(config().offsetFile());
            assertTrue(restarted.stage(update).done());
            assertTrue(restarted.pending().isEmpty());
            assertTrue(Files.readString(directory.resolve("offset.jobs/100.properties")).contains("failure-code=403"));
            assertTrue(jobs.submit(1, () -> {}));
            restarted.prune(101);
            assertFalse(Files.exists(directory.resolve("offset.jobs/100.properties")));
        } finally {
            BotJournal.set(null);
            jobs.stop();
            jobs.awaitTermination(Duration.ofSeconds(3));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 409, 429})
    void globalErrorsKeepUpdatePendingWithoutAdvancingOffset(int code) throws Exception {
        var config = config();
        var client = mock(TelegramClient.class);
        var jobs = new BotJobs();
        var attempts = new CountDownLatch(2);
        when(client.updates(anyLong())).thenReturn(json.createArrayNode().add(message(100, 1, "/start")));
        when(client.screen(eq(1L), anyString(), any())).thenAnswer(call -> {
            attempts.countDown();
            throw new TelegramApiException(1, code);
        });
        var handler = new TelegramHandler(client, config, new BotUserStore(config), jobs, null);
        var poller = new TelegramPoller(client, handler, config, jobs);
        try {
            poller.start();
            assertTrue(attempts.await(4, TimeUnit.SECONDS));
        } finally {
            poller.stop();
        }
        assertFalse(Files.exists(config.offsetFile()));
        assertEquals(1, new BotJournal(config.offsetFile()).pending().size());
        verify(client, times(1)).updates(anyLong());
    }

    @Test
    void rejectedMediaDeliveryIsTerminalAndCleansFiles() throws Exception {
        var client = mock(TelegramClient.class);
        var videos = mock(VideoProcessor.class);
        var photos = mock(PhotoProcessor.class);
        var hashes = new HashService();
        var users = new BotUserStore(config());
        var service = new VideoService(videos, hashes, new VideoProperties("ffmpeg", "ffprobe", Duration.ofSeconds(2), 1));
        var batches = new MediaBatchService(client, service, videos, photos, hashes, users);
        var profile = users.settings(1, "basic", 1);
        var options = new ProcessingOptions(1.01, .98);
        var item = new BotJournal(config().offsetFile()).stage(message(100, 1, "/start"));
        item.snapshot(profile, options);
        var output = new AtomicReference<Path>();
        doAnswer(call -> {
            Files.writeString(call.getArgument(1), "input");
            return null;
        }).when(client).download(anyString(), any());
        doAnswer(call -> {
            output.set(call.getArgument(1));
            Files.writeString(output.get(), "output");
            return null;
        }).when(photos).process(any(), any(), eq(.98), eq(false), eq(1));
        doAnswer(call -> { throw new TelegramApiException(1, 403); })
                .when(client).result(eq(1L), eq(10L), any(), anyString(), eq(true));
        BotJournal.set(item);
        try {
            assertEquals(403, assertThrows(TelegramApiException.class,
                    () -> batches.run(1, 10, 11, "file", true, profile, options)).errorCode());
            assertFalse(item.uncertain(), "Telegram explicitly rejected the upload");
            assertEquals(0, item.sent());
            assertFalse(Files.exists(output.get()));
            assertFalse(Files.exists(output.get().getParent()));
            assertEquals(0, users.get(1).results());
            verify(client, times(1)).result(eq(1L), eq(10L), any(), anyString(), eq(true));
            verify(client, never()).message(anyLong(), anyString());
            verify(client, never()).screen(anyLong(), anyString(), any());
            verify(client).edit(eq(1L), eq(11L), contains("Не завершено"), any());
        } finally {
            BotJournal.set(null);
        }
    }

    private JsonNode message(long update, long chat, String text) {
        return json.readTree("{\"update_id\":" + update + ",\"message\":{\"text\":\"" + text
                + "\",\"chat\":{\"id\":" + chat + ",\"type\":\"private\"}}}");
    }

    private TelegramProperties config() {
        return new TelegramProperties(true, "123:fake", directory.resolve("offset"), Set.of());
    }

    private void awaitOffset(Path path, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (Files.exists(path) && expected.equals(Files.readString(path).strip())) {
                return;
            }
            Thread.sleep(10);
        }
        assertEquals(expected, Files.readString(path).strip());
    }

    private void awaitCompletion(Path path) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (!Files.exists(path) || Files.readString(path).contains("done=true")) {
                return;
            }
            Thread.sleep(10);
        }
        assertTrue(Files.readString(path).contains("done=true"));
    }
}
