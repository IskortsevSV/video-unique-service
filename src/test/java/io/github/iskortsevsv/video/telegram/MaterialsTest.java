package io.github.iskortsevsv.video.telegram;

import io.github.iskortsevsv.video.config.TelegramProperties;
import io.github.iskortsevsv.video.config.VideoProperties;
import io.github.iskortsevsv.video.service.ProcessRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Проверяет добровольную загрузку и ограниченное хранение тестовых материалов. */
class MaterialsTest {
    @TempDir Path directory;
    private final JsonMapper json = new JsonMapper();
    private final ProcessRunner runner = new ProcessRunner();
    private final VideoProperties video = new VideoProperties(
            System.getenv().getOrDefault("FFMPEG_PATH", "ffmpeg"),
            System.getenv().getOrDefault("FFPROBE_PATH", "ffprobe"), Duration.ofSeconds(15), 1);

    @Test
    void startupWithUploadsDisabledNeverAcceptsMaterialReplies() throws Exception {
        var config = config(false);
        var client = mock(TelegramClient.class);
        var jobs = mock(BotJobs.class);
        var flow = new TelegramMaterials(client, config, new FeedbackStore(config),
                new MaterialArchive(config, null), jobs);
        assertTrue(flow.handle(42, reply(10), "", false));
        assertTrue(flow.handle(42, json.readTree("{}"), "/reference", false));
        verifyNoInteractions(jobs);
        verify(client, never()).download(anyString(), any());
        assertFalse(Files.exists(directory.resolve("feedback-materials")));
    }

    @Test
    void consentAndMatchingReplyAreRequiredForAReferenceUpload() throws Exception {
        var config = config(true);
        var client = mock(TelegramClient.class);
        var jobs = mock(BotJobs.class);
        var archive = new MaterialArchive(config, new ReferenceInspector(runner, video));
        var flow = new TelegramMaterials(client, config, new FeedbackStore(config), archive, jobs);
        var botMessage = json.readTree("{\"from\":{\"is_bot\":true}}");
        assertTrue(flow.handle(42, botMessage, "/reference", false));
        var captured = ArgumentCaptor.forClass(Object.class);
        verify(client).screen(eq(42L), contains("Пример другого сервиса"), captured.capture());
        String consent = json.valueToTree(captured.getValue())
                .at("/inline_keyboard/0/0/callback_data").asText();
        String prefix = consent.substring(0, consent.lastIndexOf(':') + 1);
        assertThrows(IllegalArgumentException.class,
                () -> flow.handle(42, botMessage, prefix + "COPY", true));
        flow.handle(42, botMessage, consent, true);
        when(client.filePrompt(eq(42L), anyString())).thenReturn(10L);
        flow.handle(42, botMessage, prefix + "COPY", true);
        assertThrows(IllegalArgumentException.class, () -> flow.handle(42, reply(11), "", false));
        assertFalse(flow.handle(42, json.readTree("{\"document\":{\"file_id\":\"test\"}}"), "", false));
        assertThrows(IllegalArgumentException.class, () -> flow.handle(43, reply(10), "", false));

        Path sample = png("sample", 20);
        doAnswer(call -> {
            Files.copy(sample, call.<Path>getArgument(1));
            return null;
        }).when(client).download(eq("test"), any());
        when(jobs.submit(eq(42L), any())).thenAnswer(call -> {
            call.<Runnable>getArgument(1).run();
            return true;
        });
        flow.handle(42, reply(10), "", false);
        verify(client).screen(eq(42L), contains("20 × 12"), any());
        flow.handle(42, botMessage, prefix + "delete", true);
        assertThrows(IllegalArgumentException.class, () -> flow.handle(42, reply(10), "", false));
        verify(client, times(1)).download(anyString(), any());
    }

    @Test
    void reportsPhotoPairAndRejectsInvalidReplacementBeforeDeletingOldFile() throws Exception {
        var config = config(true);
        var probe = mock(ProcessRunner.class);
        when(probe.run(anyList(), any())).thenReturn("{}");
        var archive = new MaterialArchive(config, new ReferenceInspector(probe, video));
        String id = "r" + "1".repeat(32);
        Instant now = Instant.now();
        archive.open(id, 42);
        assertTrue(archive.add(id, 42, MaterialArchive.Role.COPY, png("copy", 20), now).contains("20 × 12"));
        assertTrue(archive.add(id, 42, MaterialArchive.Role.SOURCE, png("source", 24), now)
                .contains("24 × 12 → 20 × 12"));
        Path broken = Files.writeString(directory.resolve("fake.png"), "not a photo");
        assertThrows(IllegalArgumentException.class,
                () -> archive.add(id, 42, MaterialArchive.Role.COPY, broken, now));
        Path folder = directory.resolve("feedback-materials").resolve(id);
        assertEquals(20, ImageIO.read(folder.resolve("copy.bin").toFile()).getWidth());
        assertThrows(IllegalArgumentException.class, () -> archive.delete(id, 43));
        archive.sweep(now.plus(7, ChronoUnit.DAYS).plusSeconds(1));
        assertFalse(Files.exists(folder.resolve("copy.bin")));
        assertFalse(Files.exists(folder.resolve("source.bin")));
    }

    @Test
    void checksInputLimitAndArchiveCapacity() throws Exception {
        var config = config(true);
        var inspector = mock(ReferenceInspector.class);
        when(inspector.inspect(any())).thenReturn(new ReferenceInspector.Info(true, Map.of()));
        var archive = new MaterialArchive(config, inspector);
        String id = "r" + "2".repeat(32);
        archive.open(id, 42);
        Path file = directory.resolve("size.bin");
        try (var data = new RandomAccessFile(file.toFile(), "rw")) {
            data.setLength(20_000_001);
        }
        assertThrows(IllegalArgumentException.class,
                () -> archive.add(id, 42, MaterialArchive.Role.COPY, file, Instant.now()));
        Path big = directory.resolve("feedback-materials").resolve(id).resolve("copy.bin");
        try (var data = new RandomAccessFile(big.toFile(), "rw")) {
            data.setLength(2_000_000_000);
        }
        assertTrue(assertThrows(IllegalArgumentException.class, () -> archive.add(
                id, 42, MaterialArchive.Role.COPY, png("tiny", 16), Instant.now()))
                .getMessage().contains("Архив заполнен"));
        verifyNoInteractions(inspector);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_FFMPEG_TESTS", matches = "true")
    void probesActualVideoByContentWithoutDependingOnExtension() throws Exception {
        Path input = directory.resolve("video.bin");
        runner.run(List.of(video.ffmpeg(), "-y", "-f", "lavfi", "-i",
                "testsrc2=size=160x120:rate=24:duration=1", "-c:v", "libx264",
                "-f", "mp4", input.toString()), video.timeout());
        var info = new ReferenceInspector(runner, video).inspect(input);
        assertFalse(info.photo());
        assertEquals("160 × 120", info.fields().get("Кадр"));
        assertEquals("24/1", info.fields().get("Частота кадров"));
        assertEquals("h264", info.fields().get("Видеокодек"));
        assertEquals("Нет аудио", info.fields().get("Аудиокодек"));
    }

    private TelegramProperties config(boolean enabled) {
        return new TelegramProperties(true, "123:fake", directory.resolve("offset"), Set.of(),
                8081, null, Set.of(), enabled);
    }

    private Path png(String name, int width) throws Exception {
        Path file = directory.resolve(name + ".png");
        ImageIO.write(new BufferedImage(width, 12, BufferedImage.TYPE_INT_RGB), "png", file.toFile());
        return file;
    }

    private tools.jackson.databind.JsonNode reply(long prompt) {
        return json.valueToTree(Map.of(
                "document", Map.of("file_id", "test"),
                "reply_to_message", Map.of("from", Map.of("is_bot", true),
                        "text", "📎 VUniq · Обработанная копия", "message_id", prompt)));
    }
}
