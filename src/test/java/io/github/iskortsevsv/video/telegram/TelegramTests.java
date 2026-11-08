package io.github.iskortsevsv.video.telegram;

import io.github.iskortsevsv.video.config.TelegramProperties;
import io.github.iskortsevsv.video.dto.ProcessingOptions;
import io.github.iskortsevsv.video.service.VideoService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TelegramTests {
    @TempDir Path directory;
    final JsonMapper json = new JsonMapper();
    TelegramProperties config(Set<Long> allowed) { return new TelegramProperties(true, "123:fake_test", directory.resolve("offset"), allowed); }
    @Test void parsesDefaultsAndOverridesAndRejectsBadCaptions() {
        assertEquals(new ProcessingOptions(1.01, .98), TelegramHandler.options(""));
        assertEquals(new ProcessingOptions(1.2, .9), TelegramHandler.options("scale=0.9 speed=1.2"));
        for (String caption : new String[]{"speed=NaN", "scale=Infinity", "speed=0", "scale=1.1", "test", "speed=x", "speed=1 speed=2"})
            assertThrows(IllegalArgumentException.class, () -> TelegramHandler.options(caption));
    }
    @Test void handlesVideoAndReturnsHashes() throws Exception {
        var client = mock(TelegramClient.class);
        var service = mock(VideoService.class);
        var handler = new TelegramHandler(client, service, config(Set.of()));
        doAnswer(call -> {
            VideoService.InputWriter writer = call.getArgument(1);
            Path input = directory.resolve("input.mp4"); writer.write(input);
            VideoService.ResultConsumer consumer = call.getArgument(3);
            consumer.accept(directory.resolve("output.mp4"), "before-hash", "after-hash");
            return null;
        }).when(service).process(anyString(), any(VideoService.InputWriter.class), any(), any());
        handler.handle(json.readTree("""
                {"message":{"chat":{"id":42,"type":"private"},"video":{"file_id":"abc","file_size":100},"caption":"speed=1.2"}}
                """));
        verify(service).process(eq("video.mp4"), any(VideoService.InputWriter.class), eq(new ProcessingOptions(1.2, .98)), any());
        verify(client).download(eq("abc"), any());
        verify(client).document(eq(42L), any(), contains("before-hash\nХеш после: after-hash"));
    }
    @Test void ignoresGroupsAndUnauthorizedChats() {
        var client = mock(TelegramClient.class); var service = mock(VideoService.class);
        var handler = new TelegramHandler(client, service, config(Set.of(99L)));
        handler.handle(json.readTree("{\"message\":{\"chat\":{\"id\":42,\"type\":\"private\"},\"text\":\"/start\"}}"));
        handler.handle(json.readTree("{\"message\":{\"chat\":{\"id\":99,\"type\":\"group\"},\"text\":\"/start\"}}"));
        verifyNoInteractions(client, service);
    }
    @Test void rejectsOversizedVideoBeforeDownloading() {
        var client = mock(TelegramClient.class); var service = mock(VideoService.class);
        new TelegramHandler(client, service, config(Set.of())).handle(json.readTree("""
                {"message":{"chat":{"id":42,"type":"private"},"document":{"file_id":"abc","file_name":"x.mp4","file_size":20000001}}}
                """));
        verifyNoInteractions(service);
        verify(client).message(eq(42L), contains("20 МБ"));
    }
    @Test void persistsOffsetAndRedactsConfiguration() throws Exception {
        var config = config(Set.of());
        var poller = new TelegramPoller(mock(TelegramClient.class), mock(TelegramHandler.class), config);
        poller.save(123); poller.save(124);
        assertEquals("124", Files.readString(config.offsetFile()));
        assertFalse(config.toString().contains("fake_test"));
        assertThrows(IllegalArgumentException.class, () -> new TelegramProperties(true, "", directory, Set.of()));
    }
}
