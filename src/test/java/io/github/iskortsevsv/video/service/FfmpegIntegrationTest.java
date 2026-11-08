package io.github.iskortsevsv.video.service;

import io.github.iskortsevsv.video.config.VideoProperties;
import io.github.iskortsevsv.video.dto.ProcessingOptions;
import io.github.iskortsevsv.video.exception.VideoException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "RUN_FFMPEG_TESTS", matches = "true")
class FfmpegIntegrationTest {
    @TempDir Path directory;
    final ProcessRunner runner = new ProcessRunner();
    final String ffmpeg = System.getenv().getOrDefault("FFMPEG_PATH", "ffmpeg");
    final String ffprobe = System.getenv().getOrDefault("FFPROBE_PATH", "ffprobe");
    final Duration timeout = Duration.ofSeconds(30);
    final VideoProcessor processor = new VideoProcessor(runner, new VideoProperties(ffmpeg, ffprobe, timeout, 1));
    @Test void transcodesAudioAndSilentInputs() throws Exception {
        for (boolean audio : List.of(true, false)) {
            Path input = directory.resolve("input" + audio + ".mp4");
            Path output = directory.resolve("output" + audio + ".mp4");
            var command = new ArrayList<>(List.of(ffmpeg, "-y", "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=30:duration=2"));
            if (audio) command.addAll(List.of("-f", "lavfi", "-i", "sine=frequency=440:duration=2", "-c:a", "aac"));
            command.addAll(List.of("-c:v", "libx264", "-pix_fmt", "yuv420p", input.toString()));
            runner.run(command, timeout);
            processor.process(input, output, new ProcessingOptions(1.25, .98));
            String probe = runner.run(List.of(ffprobe, "-v", "error", "-show_entries", "stream=codec_name:format=duration", "-of", "default=noprint_wrappers=1", output.toString()), timeout);
            assertTrue(probe.contains("codec_name=h264"));
            assertEquals(audio, probe.contains("codec_name=aac"));
            double duration = Double.parseDouble(probe.lines().filter(s -> s.startsWith("duration=")).findFirst().orElseThrow().substring(9));
            assertTrue(duration > 1.4 && duration < 1.9, probe);
            assertNotEquals(new HashService().sha256(input), new HashService().sha256(output));
        }
    }
    @Test void rejectsDisguisedGarbage() throws Exception {
        Path input = Files.writeString(directory.resolve("bad.mp4"), "not a video");
        assertEquals(422, assertThrows(VideoException.class,
                () -> processor.process(input, directory.resolve("out.mp4"), new ProcessingOptions(1.01, .98))).status().value());
    }
    @Test void hardModeReportsRealProgressAndChangesVideo() throws Exception {
        Path input = directory.resolve("input.mp4"), basic = directory.resolve("basic.mp4"), hard = directory.resolve("hard.mp4");
        runner.run(List.of(ffmpeg, "-y", "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=30:duration=2", "-c:v", "libx264", input.toString()), timeout);
        var options = new ProcessingOptions(1.04, .94);
        processor.process(input, basic, options);
        var progress = new java.util.concurrent.CopyOnWriteArrayList<Integer>();
        processor.process(input, hard, options, true, progress::add);
        assertFalse(progress.isEmpty()); assertEquals(100, progress.getLast());
        assertTrue(progress.stream().anyMatch(value -> value < 100));
        assertTrue(progress.stream().allMatch(value -> value >= 0 && value <= 100));
        assertNotEquals(new HashService().sha256(basic), new HashService().sha256(hard));
    }
}
