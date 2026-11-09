package io.github.iskortsevsv.video.dto;

import org.springframework.http.HttpStatus;

import io.github.iskortsevsv.video.exception.VideoException;

/** Проверенные параметры изменения скорости и масштаба видео. */
public record ProcessingOptions(double speed, double scale) {
    public ProcessingOptions {
        if (!Double.isFinite(speed)
                || speed < 0.5
                || speed > 2.0
                || !Double.isFinite(scale)
                || scale < 0.5
                || scale > 1.0) {
            throw new VideoException(
                    HttpStatus.BAD_REQUEST,
                    "speed must be between 0.5 and 2.0; scale between 0.5 and 1.0");
        }
    }
}
