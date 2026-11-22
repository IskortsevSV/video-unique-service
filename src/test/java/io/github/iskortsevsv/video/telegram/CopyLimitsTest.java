package io.github.iskortsevsv.video.telegram;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import io.github.iskortsevsv.video.config.TelegramProperties;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

/** Проверяет лимиты при выборе количества и при постановке задания из старого профиля. */
class CopyLimitsTest {
    @TempDir Path directory;
    private final JsonMapper json = new JsonMapper();

    @ParameterizedTest
    @CsvSource({"false,false,1", "false,true,3", "true,false,3", "true,true,10"})
    void enforcesTierInKeyboardSettingsAndJobs(boolean high, boolean photo, int limit) throws Exception {
        var config = new TelegramProperties(false, "", directory.resolve("offset"), Set.of(),
                8081, null, Set.of(), false, false, high ? Set.of(42L) : Set.of());
        var client = mock(TelegramClient.class);
        var jobs = mock(BotJobs.class);
        var batches = mock(MediaBatchService.class);
        var users = new BotUserStore(config);
        var handler = TelegramTestSupport.handler(client, config, users, jobs, batches);
        when(jobs.submit(eq(42L), any())).thenAnswer(call -> {
            call.<Runnable>getArgument(1).run();
            return true;
        });
        Path old = directory.resolve("users/42.properties");
        Files.createDirectories(old.getParent());
        Files.writeString(old, "copies=10\nmode=basic\n");
        String prefix = photo ? "photo:" : "video:";
        String keyboard = json.writeValueAsString(BotScreens.settingsKeys(users.get(42),
                high ? CopyAccess.HIGH : CopyAccess.BASIC));
        assertTrue(keyboard.contains("\"" + prefix + limit + "\""));
        assertFalse(keyboard.contains("\"" + prefix + (limit + 1) + "\""));

        var upload = json.readTree("""
                {"message":{"message_id":7,"chat":{"id":42,"type":"private"},
                 "document":{"file_id":"file","file_name":"%s"}}}
                """.formatted(photo ? "image.png" : "movie.mov"));
        handler.handle(upload);
        verify(batches).run(eq(42L), eq(7L), anyLong(), eq("file"), eq(photo),
                eq(new BotJournal.Snapshot("basic", limit, 1.01, .98)));

        click(handler, "mode:hard");
        click(handler, prefix + 1);
        assertEquals(1, photo ? users.get(42).photoCopies() : users.get(42).videoCopies());
        click(handler, prefix + limit);
        var selected = users.get(42);
        assertEquals(limit, photo ? selected.photoCopies() : selected.videoCopies());
        assertEquals(10, photo ? selected.videoCopies() : selected.photoCopies());
        click(handler, prefix + (limit + 1));
        verify(client).message(eq(42L), contains("от 1 до " + limit));
        click(handler, "copies:9");
        assertEquals(selected, users.get(42));
        handler.handle(upload);
        verify(batches).run(eq(42L), eq(7L), anyLong(), eq("file"), eq(photo),
                eq(new BotJournal.Snapshot("hard", limit, 1.04, .94)));
    }

    private void click(TelegramHandler handler, String action) {
        handler.handle(json.readTree("""
                {"callback_query":{"id":"click","from":{"id":42},"data":"%s",
                 "message":{"message_id":5,"chat":{"id":42,"type":"private"}}}}
                """.formatted(action)));
    }
}
