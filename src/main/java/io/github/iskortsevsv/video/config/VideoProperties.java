package io.github.iskortsevsv.video.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("video")
public record VideoProperties(@DefaultValue("ffmpeg") String ffmpeg,
        @DefaultValue("ffprobe") String ffprobe,
        @DefaultValue("2m") Duration timeout,
        @DefaultValue("2") int maxConcurrent) {
    public VideoProperties {
        if (timeout == null || timeout.isNegative() || timeout.isZero() || maxConcurrent < 1) {
            throw new IllegalArgumentException("Positive timeout and concurrency are required");
        }
    }
}
