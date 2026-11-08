package io.github.iskortsevsv.video.service;

import io.github.iskortsevsv.video.config.VideoProperties;
import io.github.iskortsevsv.video.dto.ProcessingOptions;
import io.github.iskortsevsv.video.exception.VideoException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.nio.file.Path;
import java.util.*;

@Service
public class VideoProcessor {
    private final ProcessRunner runner;
    private final VideoProperties config;
    public VideoProcessor(ProcessRunner runner, VideoProperties config) { this.runner = runner; this.config = config; }
    public void process(Path input, Path output, ProcessingOptions options) {
        String probe = runner.run(List.of(config.ffprobe(), "-v", "error", "-protocol_whitelist", "file",
                "-show_entries", "format=format_name:stream=codec_type", "-of", "default=noprint_wrappers=1",
                input.toString()), config.timeout());
        if (!probe.contains("codec_type=video") || !probe.contains("mp4")) {
            throw new VideoException(HttpStatus.UNPROCESSABLE_CONTENT, "An MP4/MOV container with a video track is required");
        }
        runner.run(command(input, output, options, probe.contains("codec_type=audio")), config.timeout());
    }
    List<String> command(Path input, Path output, ProcessingOptions options, boolean audio) {
        String filter = String.format(Locale.ROOT,
                "scale=trunc(iw*%.8f/2)*2:trunc(ih*%.8f/2)*2,pad=ceil(iw/%.8f/2)*2:ceil(ih/%.8f/2)*2:(ow-iw)/2:(oh-ih)/2:black,setsar=1,setpts=(PTS-STARTPTS)/%.8f",
                options.scale(), options.scale(), options.scale(), options.scale(), options.speed());
        var args = new ArrayList<>(List.of(config.ffmpeg(), "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
                "-protocol_whitelist", "file", "-i", input.toString(), "-map", "0:v:0", "-vf", filter,
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-pix_fmt", "yuv420p", "-threads", "2"));
        if (audio) args.addAll(List.of("-map", "0:a:0", "-af", "asetpts=PTS-STARTPTS,atempo=" + options.speed(), "-c:a", "aac", "-b:a", "128k"));
        else args.add("-an");
        args.addAll(List.of("-map_metadata", "-1", "-map_chapters", "-1", "-movflags", "+faststart", output.toString()));
        return List.copyOf(args);
    }
}
