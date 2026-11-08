package io.github.iskortsevsv.video.telegram;

import io.github.iskortsevsv.video.config.*;
import io.github.iskortsevsv.video.dto.ProcessingOptions;
import io.github.iskortsevsv.video.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BotDesignTest {
    @TempDir Path directory;
    TelegramProperties config() { return new TelegramProperties(false, "", directory.resolve("offset"), Set.of()); }
    @Test void persistsSettingsAndResetsMonthlyStatistics() throws Exception {
        var november = Clock.fixed(Instant.parse("2026-11-30T12:00:00Z"), ZoneId.of("Europe/Moscow"));
        var users = new BotUserStore(config(), november);
        assertEquals(3, users.get(42).copies());
        users.settings(42, "hard", 10); users.delivered(42, true); users.delivered(42, false);
        var restored = new BotUserStore(config(), november).get(42);
        assertTrue(restored.hard()); assertEquals(1, restored.originals()); assertEquals(2, restored.results());
        var december = new BotUserStore(config(), Clock.fixed(Instant.parse("2026-11-30T21:00:00Z"), ZoneId.of("Europe/Moscow"))).get(42);
        assertEquals(0, december.originals()); assertEquals(10, december.copies()); assertTrue(december.hard());
        assertThrows(IllegalArgumentException.class, () -> users.settings(42, "hard", 11));
    }
    @Test void callbackSettingsAndPaymentStub() throws Exception {
        var client = mock(TelegramClient.class); var jobs = mock(BotJobs.class); var batches = mock(MediaBatchService.class);
        var users = new BotUserStore(config());
        var handler = new TelegramHandler(client, config(), users, jobs, batches);
        var mapper = new JsonMapper();
        for (String action : List.of("mode:hard", "copies:5", "pay:pro")) {
            handler.handle(mapper.readTree("{\"callback_query\":{\"id\":\"cb\",\"from\":{\"id\":42},\"data\":\"" + action
                    + "\",\"message\":{\"message_id\":8,\"chat\":{\"id\":42,\"type\":\"private\"}}}}"));
        }
        assertTrue(users.get(42).hard()); assertEquals(5, users.get(42).copies());
        verify(client, times(3)).answer("cb");
        verify(client).screen(eq(42L), contains("Деньги не списываются"), any());
        verifyNoInteractions(jobs, batches);
        assertTrue(BotScreens.home("<test>").contains("&lt;test&gt;"));
        assertTrue(BotScreens.subscription(users.get(42)).contains("Ограничения по исходникам отключены"));
    }
    @Test void boundedQueueAndOneJobPerChat() throws Exception {
        var jobs = new BotJobs(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try {
            assertTrue(jobs.submit(1, () -> { entered.countDown(); try { release.await(); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); } }));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertFalse(jobs.submit(1, () -> {}));
            assertTrue(jobs.submit(2, () -> {})); assertTrue(jobs.submit(3, () -> {}));
            assertFalse(jobs.submit(4, () -> {}));
        } finally { release.countDown(); jobs.stop(); }
    }
    @Test void photoBatchDownloadsOnceAndCleansFilesAndCountsDeliveredCopies() throws Exception {
        Path source = directory.resolve("source.png");
        var picture = new BufferedImage(100, 80, BufferedImage.TYPE_INT_RGB);
        var graphics = picture.createGraphics(); graphics.setColor(java.awt.Color.ORANGE); graphics.fillRect(0, 0, 100, 80); graphics.dispose();
        ImageIO.write(picture, "png", source.toFile());
        var client = mock(TelegramClient.class); var users = new BotUserStore(config()); var hashes = new HashService();
        List<Path> workspaces = new ArrayList<>(); Set<String> results = new HashSet<>();
        doAnswer(call -> { Path destination = call.getArgument(1); workspaces.add(destination.getParent()); Files.copy(source, destination); return null; }).when(client).download(anyString(), any());
        doAnswer(call -> { Path output = call.getArgument(2); assertTrue(Files.exists(output)); results.add(hashes.sha256(output)); assertNotNull(ImageIO.read(output.toFile())); return null; })
                .when(client).result(anyLong(), anyLong(), any(), anyString(), eq(true));
        var videos = mock(VideoProcessor.class);
        var service = new VideoService(videos, hashes, new VideoProperties("f", "p", Duration.ofSeconds(10), 1));
        var batches = new MediaBatchService(client, service, videos, new PhotoProcessor(), hashes, users);
        batches.run(42, 1, 2, "photo", true, users.get(42), new ProcessingOptions(1.01, .98));
        assertEquals(3, results.size()); assertEquals(1, users.get(42).originals()); assertEquals(3, users.get(42).results());
        verify(client, times(1)).download(eq("photo"), any());
        assertFalse(Files.exists(workspaces.getFirst()));
        verify(client).edit(eq(42L), eq(2L), contains("Копия 3/3: Завершено"), any());
        verifyNoInteractions(videos);
    }
    @Test void variantsStayValidAtBoundsAndAreDifferent() {
        for (var base : List.of(new ProcessingOptions(.5, .5), new ProcessingOptions(2, 1))) {
            Set<ProcessingOptions> variants = new HashSet<>();
            for (int i = 0; i < 10; i++) variants.add(MediaBatchService.variant(base, i, true));
            assertEquals(10, variants.size());
        }
    }
    @Test void failedDeliveryDoesNotCountAndRemovesWorkspace() throws Exception {
        var client = mock(TelegramClient.class); var users = new BotUserStore(config()); var hashes = new HashService();
        var videos = mock(VideoProcessor.class); List<Path> inputs = new ArrayList<>();
        doAnswer(call -> { Path path = call.getArgument(1); inputs.add(path); Files.writeString(path, "input"); return null; }).when(client).download(anyString(), any());
        doAnswer(call -> { Files.writeString(call.getArgument(1), "output"); return null; }).when(videos).process(any(), any(), any(), anyBoolean(), any());
        doThrow(new TelegramApiException(5)).when(client).result(anyLong(), anyLong(), any(), anyString(), anyBoolean());
        var service = new VideoService(videos, hashes, new VideoProperties("f", "p", Duration.ofSeconds(10), 1));
        new MediaBatchService(client, service, videos, new PhotoProcessor(), hashes, users)
                .run(42, 1, 2, "video", false, users.get(42), new ProcessingOptions(1.01, .98));
        assertEquals(0, users.get(42).originals()); assertEquals(0, users.get(42).results());
        assertFalse(Files.exists(inputs.getFirst().getParent()));
        verify(client).edit(eq(42L), eq(2L), contains("Не завершено"), any());
        verify(client, never()).screen(anyLong(), contains("Все копии отправлены"), any());
    }
}
