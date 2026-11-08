package io.github.iskortsevsv.video.service;

import io.github.iskortsevsv.video.config.VideoProperties;
import io.github.iskortsevsv.video.dto.ProcessingOptions;
import io.github.iskortsevsv.video.exception.VideoException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VideoTests {
    @TempDir Path directory;
    @Test void rejectsNonFiniteAndOutOfRangeParameters() {
        for (double speed : new double[]{Double.NaN, Double.POSITIVE_INFINITY, 0, 2.01})
            assertThrows(VideoException.class, () -> new ProcessingOptions(speed, .98));
        for (double scale : new double[]{Double.NaN, -.1, 1.01})
            assertThrows(VideoException.class, () -> new ProcessingOptions(1.01, scale));
    }
    @Test void computesFullSha256() throws Exception {
        Path file = Files.writeString(directory.resolve("file"), "abc");
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", new HashService().sha256(file));
    }
    @Test void removesWorkspaceOnFailure() throws Exception {
        Path path;
        try (var workspace = new TemporaryWorkspace()) {
            path = workspace.file("test"); Files.writeString(path, "test");
        }
        assertFalse(Files.exists(path.getParent()));
    }
    @Test void cleansOnConsumerFailureAndReleasesSlot() throws Exception {
        var processor = mock(VideoProcessor.class);
        List<Path> inputs = new ArrayList<>();
        doAnswer(invocation -> {
            Path input = invocation.getArgument(0); inputs.add(input);
            Files.writeString(invocation.getArgument(1), "output"); return null;
        }).when(processor).process(any(), any(), any());
        var service = new VideoService(processor, new HashService(), new VideoProperties("ffmpeg", "ffprobe", Duration.ofSeconds(10), 1));
        var upload = new MockMultipartFile("file", "../video.mp4", "video/mp4", new byte[]{1});
        assertThrows(java.io.IOException.class, () -> service.process(upload, new ProcessingOptions(1, 1),
                (path, before, after) -> { throw new java.io.IOException("client disconnected"); }));
        service.process(upload, new ProcessingOptions(1, 1), (path, before, after) -> assertTrue(Files.exists(path)));
        inputs.forEach(path -> assertFalse(Files.exists(path.getParent())));
    }
    @Test void rejectsEmptyOrWrongExtension() {
        var processor = mock(VideoProcessor.class);
        var service = new VideoService(processor, new HashService(), new VideoProperties("f", "p", Duration.ofSeconds(1), 1));
        for (var file : List.of(new MockMultipartFile("file", "x.mp4", "video/mp4", new byte[0]),
                new MockMultipartFile("file", "x.txt", "video/mp4", new byte[]{1})))
            assertThrows(VideoException.class, () -> service.process(file, new ProcessingOptions(1, 1), (p,b,a) -> {}));
        verifyNoInteractions(processor);
    }
    @Test void rejectsConcurrentWorkAndCleansAfterProcessorTimeout() throws Exception {
        var processor = mock(VideoProcessor.class);
        var service = new VideoService(processor, new HashService(), new VideoProperties("f", "p", Duration.ofSeconds(1), 1));
        var upload = new MockMultipartFile("file", "video.mp4", "video/mp4", new byte[]{1});
        var options = new ProcessingOptions(1, 1);
        List<Path> inputs = new ArrayList<>();
        doAnswer(call -> {
            inputs.add(call.getArgument(0));
            assertEquals(429, assertThrows(VideoException.class,
                    () -> service.process(upload, options, (p,b,a) -> {})).status().value());
            throw new VideoException(org.springframework.http.HttpStatus.GATEWAY_TIMEOUT, "timeout");
        }).when(processor).process(any(), any(), any());
        for (int attempt = 0; attempt < 2; attempt++)
            assertEquals(504, assertThrows(VideoException.class,
                    () -> service.process(upload, options, (p,b,a) -> fail("Must not deliver output"))).status().value());
        assertEquals(2, inputs.size());
        inputs.forEach(path -> assertFalse(Files.exists(path.getParent())));
    }
    @Test void processTimeoutAndNonzeroExit() {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var runner = new ProcessRunner();
        var timeout = assertThrows(VideoException.class, () -> runner.run(List.of(java, "-cp",
                System.getProperty("java.class.path"), Sleeper.class.getName()), Duration.ofMillis(200)));
        assertEquals(504, timeout.status().value());
        assertEquals(422, assertThrows(VideoException.class,
                () -> runner.run(List.of(java, "--invalid-option"), Duration.ofSeconds(10))).status().value());
        assertEquals(503, assertThrows(VideoException.class,
                () -> runner.run(List.of("missing-executable-video-test-123"), Duration.ofSeconds(1))).status().value());
    }
    public static class Sleeper {
        public static void main(String[] args) throws Exception { Thread.sleep(30000); }
    }
}
