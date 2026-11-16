package io.github.iskortsevsv.video.telegram;

import io.github.iskortsevsv.video.config.TelegramProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Проверяет отзывы по отдельным копиям, границы доступа и подсчёт ответов. */
class FeedbackTest {
    @TempDir Path directory;

    @Test
    void countsOnlyDeliveredCopiesAndKeepsLatestVoteAfterRestart() throws Exception {
        var config = config();
        var store = new FeedbackStore(config);
        String first = store.prepare(42, 9, 1, false, "hard", "video-v2");
        String second = store.prepare(42, 9, 2, false, "hard", "video-v2");
        String third = store.prepare(42, 9, 3, false, "hard", "video-v2");
        String fourth = store.prepare(42, 9, 4, false, "hard", "video-v2");
        store.prepare(42, 9, 5, false, "hard", "video-v2");
        store.delivered(first, 42);
        store.delivered(second, 42);
        store.delivered(third, 42);
        store.delivered(fourth, 42);
        store.vote(first, 42, "ok", "Пробный Reels", "yes");
        store.vote(second, 42, "err", "Пробный Reels", "no");
        store.vote(third, 42, "wait", "", "");
        store.vote(fourth, 42, "err", "Пробный Reels", "skip");
        var restarted = new FeedbackStore(config);
        assertEquals(first, restarted.prepare(42, 9, 1, false, "hard", "video-v2"));
        restarted.delivered(second, 42);
        restarted.vote(second, 42, "dup", "Пробный Reels", "no");
        var stats = String.join("\n", restarted.stats(Instant.now()));
        assertTrue(stats.contains("Выдано 4, ответили 4/4"));
        assertTrue(stats.contains("не проверяли 1, без ответа 0"));
        assertTrue(stats.contains("Проверено 3, без предупреждения 1, повторов 1/2, других ошибок 1"));
        assertTrue(stats.contains("Видео · hard · video-v2 · Пробный Reels"));
        assertTrue(restarted.stats(Instant.now().plus(31, ChronoUnit.DAYS)).isEmpty());
    }

    @Test
    void verifiedCallbackCanConfirmDeliveryAndOnlyAdminCanReadReport() throws Exception {
        var config = config();
        var store = new FeedbackStore(config);
        String photo = store.prepare(42, 9, 1, true, "basic", "photo-v1");
        var client = mock(TelegramClient.class);
        var bot = new TelegramFeedback(client, config, store);
        var message = new JsonMapper().readTree("""
                {"from":{"is_bot":true},"message_id":42}
                """);
        assertThrows(IllegalArgumentException.class, () -> bot.handle(43, message, "fb:" + photo));
        assertTrue(store.stats(Instant.now()).isEmpty());
        bot.handle(42, message, "fb:" + photo + ":ok");
        verify(client).editButtons(eq(42L), eq(42L),
                argThat(keyboard -> keyboard.toString().contains("Карусель")));
        assertThrows(IllegalArgumentException.class,
                () -> bot.handle(42, message, "fb:" + photo + ":ok:trial:skip"));
        bot.handle(42, message, "fb:" + photo + ":ok:post:skip");
        bot.showStats(43);
        verify(client).message(43, "Команда доступна администратору.");
        bot.showStats(42);
        verify(client).message(eq(42L), contains("Фото · basic · photo-v1 · Пост"));
        assertEquals("skip", store.owned(photo, 42).getProperty("previous"));
    }

    private TelegramProperties config() {
        return new TelegramProperties(true, "123:fake", directory.resolve("offset"), Set.of(),
                8081, null, Set.of(42L), false);
    }
}
