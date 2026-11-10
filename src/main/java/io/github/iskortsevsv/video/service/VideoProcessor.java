package io.github.iskortsevsv.video.service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import io.github.iskortsevsv.video.config.VideoProperties;
import io.github.iskortsevsv.video.dto.ProcessingOptions;
import io.github.iskortsevsv.video.exception.VideoException;

/** Проверяет видео через ffprobe и преобразует его с помощью FFmpeg. */
@Service
public class VideoProcessor {
    private final ProcessRunner runner;
    private final VideoProperties config;

    public VideoProcessor(ProcessRunner runner, VideoProperties config) {
        this.runner = runner;
        this.config = config;
    }

    /**
     * Проверяет входной контейнер и записывает обработанный MP4 в режиме Basic.
     *
     * @param input путь исходного видео
     * @param output путь для создаваемого MP4
     * @param options коэффициенты скорости и масштаба
     * @throws VideoException если входной файл не поддерживается или процесс завершился ошибкой
     */
    public void process(Path input, Path output, ProcessingOptions options) {
        process(input, output, options, false, progress -> {});
    }

    /**
     * Преобразует видео и сообщает прогресс кодирования.
     *
     * @param input путь исходного видео
     * @param output путь для создаваемого MP4
     * @param options коэффициенты скорости и масштаба
     * @param hard включить дополнительную коррекцию цвета
     * @param progress приёмник процентов от 0 до 100; длительность может быть неизвестна
     * @throws VideoException если входной файл не поддерживается или процесс завершился ошибкой
     */
    public void process(
            Path input,
            Path output,
            ProcessingOptions options,
            boolean hard,
            java.util.function.IntConsumer progress) {
        process(input, output, options, hard, progress, probe(input));
    }

    /** Метаданные одного исходного видео; длительность указана до изменения скорости. */
    public record VideoInfo(boolean audio, double durationSeconds) {}

    /** Анализирует контейнер один раз для последующего создания нескольких копий. */
    public VideoInfo probe(Path input) {
        String probe =
                runner.run(
                        List.of(
                                config.ffprobe(),
                                "-v",
                                "error",
                                "-protocol_whitelist",
                                "file",
                                "-show_entries",
                                "format=format_name,duration:stream=codec_type",
                                "-of",
                                "default=noprint_wrappers=1",
                                input.toString()),
                        config.timeout());
        if (!probe.contains("codec_type=video") || !probe.contains("mp4")) {
            throw new VideoException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "An MP4/MOV container with a video track is required");
        }
        double duration =
                probe.lines()
                                .filter(line -> line.startsWith("duration="))
                                .mapToDouble(
                                        line -> {
                                            try {
                                                return Double.parseDouble(line.substring(9));
                                            } catch (NumberFormatException ex) {
                                                return 0;
                                            }
                                        })
                                .findFirst()
                                .orElse(0);
        return new VideoInfo(probe.contains("codec_type=audio"), duration);
    }

    /** Кодирует копию, используя метаданные именно переданного исходного файла. */
    public void process(Path input, Path output, ProcessingOptions options, boolean hard,
            java.util.function.IntConsumer progress, VideoInfo info) {
        process(input, output, options, hard, progress, info, null);
    }

    /** Кодирует вариант с воспроизводимыми настройками кадрирования и цвета. */
    public void process(Path input, Path output, ProcessingOptions options, boolean hard,
            java.util.function.IntConsumer progress, VideoInfo info, String variantId) {
        double duration = info.durationSeconds() / options.speed();
        var args =
                new ArrayList<>(
                        command(input, output, options, info.audio()));
        if (variantId != null) {
            int index = args.indexOf("-vf") + 1;
            args.set(index, args.get(index) + variantFilter(variantId, hard));
            args.addAll(args.size() - 1, List.of("-metadata", "comment=VUniq variant " + variantId));
        } else if (hard) {
            int index = args.indexOf("-vf") + 1;
            args.set(index, args.get(index) + ",eq=contrast=1.04:saturation=1.06:brightness=0.01");
        }
        args.addAll(1, List.of("-progress", "pipe:1", "-nostats"));
        runner.run(
                args,
                config.timeout(),
                line -> {
                    if (duration > 0
                            && Double.isFinite(duration)
                            && line.startsWith("out_time_us=")) {
                        try {
                            progress.accept(
                                    (int)
                                            Math.clamp(
                                                    Long.parseLong(line.substring(12))
                                                            / 10000.0
                                                            / duration,
                                                    0,
                                                    99));
                        } catch (NumberFormatException ignored) {
                            /* FFmpeg may report N/A. */
                        }
                    }
                });
        progress.accept(100);
    }

    private static String variantFilter(String variantId, boolean hard) {
        var random = new Random(variantId.hashCode());
        double contrast = hard ? random.nextDouble(1.07, 1.15) : random.nextDouble(.99, 1.025);
        double saturation = hard ? random.nextDouble(1.08, 1.20) : random.nextDouble(.96, 1.04);
        double brightness = hard ? random.nextDouble(-.025, .025) : random.nextDouble(-.01, .01);
        String color = String.format(Locale.ROOT,
                ",eq=contrast=%.5f:saturation=%.5f:brightness=%.5f", contrast, saturation, brightness);
        if (!hard) {
            return color;
        }
        double zoom = random.nextDouble(1.08, 1.14);
        // Round up when enlarging, then round down to recover the original even dimensions.
        String crop = String.format(Locale.ROOT,
                ",scale=ceil(iw*%.8f/2)*2:ceil(ih*%.8f/2)*2"
                        + ",crop=trunc((iw/%.8f+0.000001)/2)*2:trunc((ih/%.8f+0.000001)/2)*2,setsar=1",
                zoom, zoom, zoom, zoom);
        return crop + color;
    }

    List<String> command(Path input, Path output, ProcessingOptions options, boolean audio) {
        String filter =
                String.format(
                        Locale.ROOT,
                        "scale=trunc(iw*%.8f/2)*2:trunc(ih*%.8f/2)*2,pad=ceil(iw/%.8f/2)*2:ceil(ih/%.8f/2)*2:(ow-iw)/2:(oh-ih)/2:black,setsar=1,setpts=(PTS-STARTPTS)/%.8f",
                        options.scale(),
                        options.scale(),
                        options.scale(),
                        options.scale(),
                        options.speed());
        var args =
                new ArrayList<>(
                        List.of(
                                config.ffmpeg(),
                                "-hide_banner",
                                "-loglevel",
                                "error",
                                "-nostdin",
                                "-y",
                                "-protocol_whitelist",
                                "file",
                                "-i",
                                input.toString(),
                                "-map",
                                "0:v:0",
                                "-vf",
                                filter,
                                "-c:v",
                                "libx264",
                                "-preset",
                                "veryfast",
                                "-crf",
                                "23",
                                "-pix_fmt",
                                "yuv420p",
                                "-threads",
                                "2"));
        if (audio) {
            args.addAll(
                    List.of(
                            "-map",
                            "0:a:0",
                            "-af",
                            "asetpts=PTS-STARTPTS,atempo=" + options.speed(),
                            "-c:a",
                            "aac",
                            "-b:a",
                            "128k"));
        } else {
            args.add("-an");
        }
        args.addAll(
                List.of(
                        "-map_metadata",
                        "-1",
                        "-map_chapters",
                        "-1",
                        "-movflags",
                        "+faststart",
                        output.toString()));
        return List.copyOf(args);
    }
}
