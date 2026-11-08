package io.github.iskortsevsv.video.exception;

import org.springframework.http.HttpStatus;

public class VideoException extends RuntimeException {
    private final HttpStatus status;
    public VideoException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }
    public HttpStatus status() { return status; }
}
