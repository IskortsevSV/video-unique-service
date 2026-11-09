package io.github.iskortsevsv.video.telegram;

import static org.junit.jupiter.api.Assertions.*;

import io.github.iskortsevsv.video.dto.ProcessingOptions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;

/** Проверяет сохранение и восстановление состояния заданий Telegram. */
class BotJournalTest {
    @TempDir Path directory;

    @Test
    void recoversSettingsAndDeliveryStateUntilOffsetHasAdvanced() throws Exception {
        var update =
                new JsonMapper().readTree("{\"update_id\":100,\"message\":{\"text\":\"test\"}}");
        var journal = new BotJournal(directory.resolve("offset"));
        var item = journal.stage(update);
        var old = new BotUserStore.Profile("hard", 5, 0, 0, YearMonth.of(2026, 11));
        item.snapshot(old, new ProcessingOptions(1.04, .94));
        item.delivered(1);
        item.sending(true);

        var recovered = new BotJournal(directory.resolve("offset"));
        assertEquals(1, recovered.pending().size());
        var replay = recovered.stage(recovered.pending().getFirst());
        var changed = new BotUserStore.Profile("basic", 1, 0, 0, old.month());
        assertEquals(
                new BotJournal.Snapshot("hard", 5, 1.04, .94),
                replay.snapshot(changed, new ProcessingOptions(1.01, .98)));
        assertEquals(1, replay.sent());
        assertTrue(replay.uncertain());
        replay.complete();
        assertTrue(recovered.pending().isEmpty());
        recovered.prune(100);
        assertTrue(Files.exists(directory.resolve("offset.jobs/100.properties")));
        recovered.prune(101);
        assertFalse(Files.exists(directory.resolve("offset.jobs/100.properties")));
    }
}
