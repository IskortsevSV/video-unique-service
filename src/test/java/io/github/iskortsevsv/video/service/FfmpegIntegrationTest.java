package io.github.iskortsevsv.video.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import io.github.iskortsevsv.video.config.VideoProperties;
import io.github.iskortsevsv.video.dto.ProcessingOptions;
import io.github.iskortsevsv.video.exception.VideoException;

@EnabledIfEnvironmentVariable(named = "RUN_FFMPEG_TESTS", matches = "true")
/** Проверяет кодирование и анализ медиа с настоящими FFmpeg и ffprobe. */
class FfmpegIntegrationTest {
    @TempDir Path directory;
    final ProcessRunner runner = new ProcessRunner();
    final String ffmpeg = System.getenv().getOrDefault("FFMPEG_PATH", "ffmpeg");
    final String ffprobe = System.getenv().getOrDefault("FFPROBE_PATH", "ffprobe");
    final Duration timeout = Duration.ofSeconds(30);
    final VideoProcessor processor =
            new VideoProcessor(runner, new VideoProperties(ffmpeg, ffprobe, timeout, 1));

    @Test
    void transcodesAudioAndSilentInputs() throws Exception {
        for (boolean audio : List.of(true, false)) {
            Path input = directory.resolve("input" + audio + ".mp4");
            Path output = directory.resolve("output" + audio + ".mp4");
            var command =
                    new ArrayList<>(
                            List.of(
                                    ffmpeg,
                                    "-y",
                                    "-f",
                                    "lavfi",
                                    "-i",
                                    "testsrc2=size=320x240:rate=30:duration=2"));
            if (audio) {
                command.addAll(
                        List.of(
                                "-f",
                                "lavfi",
                                "-i",
                                "sine=frequency=440:duration=2",
                                "-c:a",
                                "aac"));
            }
            command.addAll(List.of("-c:v", "libx264", "-pix_fmt", "yuv420p", input.toString()));
            runner.run(command, timeout);
            processor.process(input, output, new ProcessingOptions(1.25, .98));
            String probe =
                    runner.run(
                            List.of(
                                    ffprobe,
                                    "-v",
                                    "error",
                                    "-show_entries",
                                    "stream=codec_name:format=duration",
                                    "-of",
                                    "default=noprint_wrappers=1",
                                    output.toString()),
                            timeout);
            assertTrue(probe.contains("codec_name=h264"));
            assertEquals(audio, probe.contains("codec_name=aac"));
            double duration =
                    Double.parseDouble(
                            probe.lines()
                                    .filter(s -> s.startsWith("duration="))
                                    .findFirst()
                                    .orElseThrow()
                                    .substring(9));
            assertTrue(duration > 1.4 && duration < 1.9, probe);
            assertNotEquals(new HashService().sha256(input), new HashService().sha256(output));
        }
    }

    @Test
    void rejectsDisguisedGarbage() throws Exception {
        Path input = Files.writeString(directory.resolve("bad.mp4"), "not a video");
        assertEquals(
                422,
                assertThrows(
                                VideoException.class,
                                () ->
                                        processor.process(
                                                input,
                                                directory.resolve("out.mp4"),
                                                new ProcessingOptions(1.01, .98)))
                        .status()
                        .value());
    }

    @Test
    void hardModeReportsRealProgressAndChangesVideo() throws Exception {
        Path input = directory.resolve("input.mp4"),
                basic = directory.resolve("basic.mp4"),
                hard = directory.resolve("hard.mp4");
        runner.run(
                List.of(
                        ffmpeg,
                        "-y",
                        "-f",
                        "lavfi",
                        "-i",
                        "testsrc2=size=320x240:rate=30:duration=2",
                        "-c:v",
                        "libx264",
                        input.toString()),
                timeout);
        var options = new ProcessingOptions(1.04, .94);
        processor.process(input, basic, options);
        var progress = new java.util.concurrent.CopyOnWriteArrayList<Integer>();
        processor.process(input, hard, options, true, progress::add);
        assertFalse(progress.isEmpty());
        assertEquals(100, progress.getLast());
        assertTrue(progress.stream().anyMatch(value -> value < 100));
        assertTrue(progress.stream().allMatch(value -> value >= 0 && value <= 100));
        assertNotEquals(new HashService().sha256(basic), new HashService().sha256(hard));
    }

    @Test
    void newBatchesChangeDecodedVideoAndReplayKeepsTheSameVariant() throws Exception {
        Path input = directory.resolve("source.mp4");
        runner.run(List.of(ffmpeg, "-y", "-f", "lavfi", "-i",
                "testsrc2=size=180x320:rate=30:duration=1", "-c:v", "libx264", input.toString()), timeout);
        var options = new ProcessingOptions(1.04, .94);
        var info = processor.probe(input);
        var hashes = new HashService();
        for (boolean hard : List.of(false, true)) {
            Path first = directory.resolve("first-" + hard + ".mp4");
            Path second = directory.resolve("second-" + hard + ".mp4");
            Path replay = directory.resolve("replay-" + hard + ".mp4");
            processor.process(input, first, options, hard, percent -> {}, info, "job-a-0");
            processor.process(input, second, options, hard, percent -> {}, info, "job-b-0");
            processor.process(input, replay, options, hard, percent -> {}, info, "job-a-0");

            assertNotEquals(decodedVideoHash(first), decodedVideoHash(second));
            assertEquals(hashes.sha256(first), hashes.sha256(replay));
        }
        assertEquals(videoDimensions(directory.resolve("first-false.mp4")),
                videoDimensions(directory.resolve("first-true.mp4")));
    }

    private String decodedVideoHash(Path file) {
        return runner.run(List.of(ffmpeg, "-v", "error", "-i", file.toString(),
                "-map", "0:v:0", "-c:v", "rawvideo", "-f", "hash", "-"), timeout).strip();
    }

    private String videoDimensions(Path file) {
        return runner.run(List.of(ffprobe, "-v", "error", "-select_streams", "v:0",
                "-show_entries", "stream=width,height", "-of", "csv=p=0", file.toString()),
                timeout).strip();
    }
}
