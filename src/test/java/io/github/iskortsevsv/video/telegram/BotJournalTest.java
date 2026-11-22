package io.github.iskortsevsv.video.telegram;

import static org.junit.jupiter.api.Assertions.*;


import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;

/** Проверяет сохранение и восстановление состояния заданий Telegram. */
class BotJournalTest {
    @TempDir Path directory;

    @Test
    void recoversLegacyJobWithoutChangingItsRenderParameters() throws Exception {
        Path offset = directory.resolve("offset");
        var update = new JsonMapper().readTree("{\"update_id\":100}");
        var profile = new BotJournal.Snapshot("hard", 3, 1.04, .94);
        new BotJournal(offset).stage(update).snapshot(profile);
        Path record = directory.resolve("offset.jobs/100.properties");
        Files.write(record, Files.readAllLines(record).stream()
                .filter(line -> !line.startsWith("variant-key=") && !line.startsWith("mirrorVideo="))
                .toList());

        var restored = new BotJournal(offset).stage(update);
        assertEquals(new BotJournal.Snapshot("hard", 3, 1.04, .94),
                restored.snapshot(new BotJournal.Snapshot("hard", 3, 1.04, .94, true)));
        assertNull(restored.variantKey());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void recoversSettingsAndDeliveryStateUntilOffsetHasAdvanced(boolean nextDeliveryUncertain) throws Exception {
        var update =
                new JsonMapper().readTree("{\"update_id\":100,\"message\":{\"text\":\"test\"}}");
        var journal = new BotJournal(directory.resolve("offset"));
        var item = journal.stage(update);
        var old = new BotJournal.Snapshot("hard", 5, 1.04, .94, true);
        item.snapshot(old);
        String originalKey = item.variantKey();
        assertNotNull(originalKey);
        var next = journal.stage(new JsonMapper().readTree("{\"update_id\":101}"));
        next.snapshot(old);
        assertNotEquals(originalKey, next.variantKey());
        next.complete();
        item.delivered(1);
        if (nextDeliveryUncertain) {
            item.sending(true);
        }

        var recovered = new BotJournal(directory.resolve("offset"));
        assertEquals(1, recovered.pending().size());
        var replay = recovered.stage(recovered.pending().getFirst());
        var changed = new BotJournal.Snapshot("basic", 1, 1.01, .98);
        assertEquals(
                old,
                replay.snapshot(changed));
        assertEquals(1, replay.sent());
        assertEquals(nextDeliveryUncertain, replay.uncertain());
        assertEquals(originalKey, replay.variantKey());
        replay.complete();
        assertTrue(recovered.pending().isEmpty());
        recovered.prune(100);
        assertTrue(Files.exists(directory.resolve("offset.jobs/100.properties")));
        recovered.prune(101);
        assertFalse(Files.exists(directory.resolve("offset.jobs/100.properties")));
    }
}
