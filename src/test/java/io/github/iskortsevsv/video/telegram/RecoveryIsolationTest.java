package io.github.iskortsevsv.video.telegram;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import io.github.iskortsevsv.video.config.TelegramProperties;

/** Проверяет карантин повреждённых записей и безопасную остановку обработки. */
class RecoveryIsolationTest {
    @TempDir Path directory;
    private final JsonMapper json = new JsonMapper();

    @ParameterizedTest
    @ValueSource(strings = {"base64", "json", "identity", "counter", "negative", "sending", "snapshot", "empty", "done"})
    void quarantinesCorruptRecordAndPreservesHealthyJob(String defect) throws Exception {
        var journal = new BotJournal(directory.resolve("offset"));
        var update = json.readTree("{\"update_id\":1}");
        journal.stage(update);
        journal.stage(json.readTree("{\"update_id\":2}"));
        String encoded = Base64.getEncoder().encodeToString(update.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String data = "update=" + encoded + "\nsent=0\n";
        data = switch (defect) {
            case "base64" -> "update=%%%\nsent=0\n";
            case "json" -> "update=" + Base64.getEncoder().encodeToString("{".getBytes()) + "\nsent=0\n";
            case "identity" -> "update=" + Base64.getEncoder().encodeToString("{\"update_id\":9}".getBytes()) + "\nsent=0\n";
            case "counter" -> data.replace("sent=0", "sent=oops");
            case "negative" -> data.replace("sent=0", "sent=-1");
            case "sending" -> data + "sending=unknown\n";
            case "snapshot" -> data + "mode=hard\ncopies=5\nspeed=NaN\nscale=0.94\n";
            case "empty" -> "";
            default -> data + "done=true\n";
        };
        Path corrupt = directory.resolve("offset.jobs/1.properties");
        Files.writeString(corrupt, data);
        assertEquals(2, journal.pending().getFirst().path("update_id").asLong());
        assertEquals(1, journal.pending().size());
        assertEquals(data, Files.readString(directory.resolve("offset.jobs/quarantine/1.properties")));
        assertFalse(Files.exists(corrupt));
        journal.prune(100);
        var restarted = new BotJournal(directory.resolve("offset"));
        assertTrue(restarted.stage(update).done(), "Uncertain delivery must not be replayed");
    }

    @Test
    void stageAndPruneAlsoIsolateCorruptRecords() throws Exception {
        var journal = new BotJournal(directory.resolve("offset"));
        var update = json.readTree("{\"update_id\":1}");
        journal.stage(update);
        Files.writeString(directory.resolve("offset.jobs/1.properties"), "update=%%%\n");
        assertTrue(journal.stage(update).done());
        journal.stage(json.readTree("{\"update_id\":2}"));
        Files.writeString(directory.resolve("offset.jobs/2.properties"), "sent=bad\n");
        assertDoesNotThrow(() -> journal.prune(100));
        assertTrue(Files.exists(directory.resolve("offset.jobs/quarantine/2.properties")));
    }

    @Test
    void pollingContinuesAfterQuarantine() throws Exception {
        var config = config();
        var journal = new BotJournal(config.offsetFile());
        journal.stage(json.readTree("{\"update_id\":1}"));
        Files.writeString(directory.resolve("offset.jobs/1.properties"), "update=%%%\n");
        var client = mock(TelegramClient.class);
        var polled = new CountDownLatch(1);
        when(client.updates(anyLong())).thenAnswer(call -> {polled.countDown(); Thread.sleep(20); return json.readTree("[]");});
        var poller = new TelegramPoller(client, mock(TelegramHandler.class), config, new BotJobs());
        try {poller.start(); assertTrue(polled.await(3, TimeUnit.SECONDS));}
        finally {poller.stop();}
    }

    @Test
    void retainsOwnershipUntilActiveWorkerExitsAndKeepsQueuedJobsForRecovery() throws Exception {
        var config = config();
        var jobs = new BotJobs();
        var client = mock(TelegramClient.class);
        var handler = mock(TelegramHandler.class);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var queued = new CountDownLatch(1);
        var queuedRuns = new AtomicInteger();
        var calls = new AtomicInteger();
        when(client.updates(anyLong())).thenAnswer(call -> {Thread.sleep(10); return json.readTree(calls.getAndIncrement() == 0 ? "[{\"update_id\":100},{\"update_id\":101}]" : "[]");});
        doAnswer(call -> {
            long id = ((tools.jackson.databind.JsonNode) call.getArgument(0)).path("update_id").asLong();
            if (id == 100) {
                jobs.submit(id, () -> {
                    entered.countDown();
                    boolean waiting = true;
                    while (waiting) {
                        try {release.await(); waiting = false;}
                        catch (InterruptedException ignored) { /* Simulate a network call that ignores interrupts. */ }
                    }
                });
            } else {jobs.submit(id, queuedRuns::incrementAndGet); queued.countDown();}
            return null;
        }).when(handler).handle(any());
        var first = new TelegramPoller(client, handler, config, jobs);
        var second = new TelegramPoller(mock(TelegramClient.class), mock(TelegramHandler.class), config, new BotJobs());
        try {
            first.start();
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertTrue(queued.await(3, TimeUnit.SECONDS));
            first.stop(Duration.ofMillis(100));
            assertThrows(RejectedExecutionException.class, () -> jobs.submit(300, () -> {}));
            assertThrows(IllegalStateException.class, second::start);
            assertEquals(0, queuedRuns.get());
            release.countDown();
            assertTrue(jobs.awaitTermination(Duration.ofSeconds(3)));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            boolean unlocked = false;
            while (!unlocked && System.nanoTime() < deadline) {
                try (var channel = FileChannel.open(directory.resolve("offset.lock"), StandardOpenOption.WRITE)) {
                    try (var lock = channel.tryLock()) {unlocked = lock != null;}
                    catch (OverlappingFileLockException ignored) { }
                }
                if (!unlocked) {Thread.sleep(10);}
            }
            assertTrue(unlocked);
            assertEquals(2, new BotJournal(config.offsetFile()).pending().size());
            assertDoesNotThrow(second::start);
        } finally {release.countDown(); first.stop(); second.stop(); jobs.stop(); jobs.awaitTermination(Duration.ofSeconds(3));}
    }

    private TelegramProperties config() {
        return new TelegramProperties(true, "123:fake", directory.resolve("offset"), Set.of());
    }
}
