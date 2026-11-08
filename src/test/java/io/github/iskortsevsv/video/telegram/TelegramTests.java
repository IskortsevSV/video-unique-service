package io.github.iskortsevsv.video.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.contains;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import io.github.iskortsevsv.video.config.TelegramProperties;
import io.github.iskortsevsv.video.dto.ProcessingOptions;

/** Проверяет маршрутизацию, настройки и polling Telegram-бота. */
class TelegramTests {
    @TempDir Path directory;
    final JsonMapper json = new JsonMapper();

    TelegramProperties config(Set<Long> allowed) {
        return new TelegramProperties(true, "123:fake_test", directory.resolve("offset"), allowed);
    }

    @Test
    void parsesDefaultsAndOverridesAndRejectsBadCaptions() {
        assertEquals(new ProcessingOptions(1.01, .98), TelegramHandler.options(""));
        assertEquals(
                new ProcessingOptions(1.2, .9), TelegramHandler.options("scale=0.9 speed=1.2"));
        for (String caption :
                new String[] {
                    "speed=NaN",
                    "scale=Infinity",
                    "speed=0",
                    "scale=1.1",
                    "test",
                    "speed=x",
                    "speed=1 speed=2"
                }) {
            assertThrows(IllegalArgumentException.class, () -> TelegramHandler.options(caption));
        }
    }

    @Test
    void handlesVideoAndReturnsHashes() throws Exception {
        var client = mock(TelegramClient.class);
        var batches = mock(MediaBatchService.class);
        var jobs = mock(BotJobs.class);
        var config = config(Set.of());
        var handler = new TelegramHandler(client, config, new BotUserStore(config), jobs, batches);
        doAnswer(
                        call -> {
                            ((Runnable) call.getArgument(1)).run();
                            return true;
                        })
                .when(jobs)
                .submit(anyLong(), any());
        handler.handle(
                json.readTree(
                        """
                        {"message":{"message_id":1,"chat":{"id":42,"type":"private"},"video":{"file_id":"abc","file_size":100},"caption":"speed=1.2"}}
                        """));
        verify(batches)
                .run(
                        eq(42L),
                        anyLong(),
                        anyLong(),
                        eq("abc"),
                        eq(false),
                        argThat(p -> p.copies() == 3),
                        eq(new ProcessingOptions(1.2, .98)));
    }

    @Test
    void ignoresGroupsAndUnauthorizedChats() {
        var client = mock(TelegramClient.class);
        var service = mock(MediaBatchService.class);
        var config = config(Set.of(99L));
        var handler =
                new TelegramHandler(
                        client, config, new BotUserStore(config), mock(BotJobs.class), service);
        handler.handle(
                json.readTree(
                        "{\"message\":{\"chat\":{\"id\":42,\"type\":\"private\"},\"text\":\"/start\"}}"));
        handler.handle(
                json.readTree(
                        "{\"message\":{\"chat\":{\"id\":99,\"type\":\"group\"},\"text\":\"/start\"}}"));
        verifyNoInteractions(client, service);
    }

    @Test
    void rejectsOversizedVideoBeforeDownloading() {
        var client = mock(TelegramClient.class);
        var service = mock(MediaBatchService.class);
        var config = config(Set.of());
        new TelegramHandler(client, config, new BotUserStore(config), mock(BotJobs.class), service)
                .handle(
                        json.readTree(
                                """
                                {"message":{"chat":{"id":42,"type":"private"},"document":{"file_id":"abc","file_name":"x.mp4","file_size":20000001}}}
                                """));
        verifyNoInteractions(service);
        verify(client).message(eq(42L), contains("20 МБ"));
    }

    @Test
    void persistsOffsetAndRedactsConfiguration() throws Exception {
        var config = config(Set.of());
        var poller =
                new TelegramPoller(mock(TelegramClient.class), mock(TelegramHandler.class), config, new BotJobs());
        poller.save(123);
        poller.save(124);
        assertEquals("124", Files.readString(config.offsetFile()));
        assertFalse(config.toString().contains("fake_test"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TelegramProperties(true, "", directory, Set.of()));
    }

    @Test
    void preventsTwoPollersSharingTheSameOffsetFile() throws Exception {
        var config = config(Set.of());
        var first =
                new TelegramPoller(mock(TelegramClient.class), mock(TelegramHandler.class), config, new BotJobs());
        var second =
                new TelegramPoller(mock(TelegramClient.class), mock(TelegramHandler.class), config, new BotJobs());
        first.start();
        try {
            assertThrows(IllegalStateException.class, second::start);
        } finally {
            first.stop();
        }
        second.start();
        second.stop();
    }
}
