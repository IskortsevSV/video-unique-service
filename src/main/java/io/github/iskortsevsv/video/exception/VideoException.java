package io.github.iskortsevsv.video.exception;

import org.springframework.http.HttpStatus;

/** Ошибка обработки с HTTP-статусом для преобразования в ответ API. */
public class VideoException extends RuntimeException {
    private final HttpStatus status;

    public VideoException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    /** Возвращает HTTP-статус ошибки. */
    public HttpStatus status() {
        return status;
    }
}
