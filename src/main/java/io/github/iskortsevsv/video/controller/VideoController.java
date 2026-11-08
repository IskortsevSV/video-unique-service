package io.github.iskortsevsv.video.controller;

import java.io.IOException;
import java.nio.file.Files;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import io.github.iskortsevsv.video.dto.ProcessingOptions;
import io.github.iskortsevsv.video.service.VideoService;

/**
 * HTTP API загрузки и обработки видео.
 */
@RestController
@RequestMapping("/api/videos")
public class VideoController {

    private final VideoService service;

    public VideoController(VideoService service) {
        this.service = service;
    }

    /**
     * Обрабатывает загруженное видео и отправляет результат с хешами исходного и выходного файлов.
     *
     * @param file исходное видео
     * @param speed коэффициент скорости
     * @param scale коэффициент масштаба
     * @param response HTTP-ответ для потоковой передачи результата
     * @throws IOException если не удалось прочитать или передать файл
     */
    @Operation(
            summary = "Re-encode a video with speed and scale adjustments",
            description =
                    "SHA-256 indicates byte differences only, not uniqueness on social platforms. MOV inputs are also accepted.")
    @ApiResponse(
            responseCode = "200",
            content =
                    @Content(
                            mediaType = "video/mp4",
                            schema = @Schema(type = "string", format = "binary")),
            headers = {
                @Header(
                        name = "X-Hash-Before",
                        description = "Original SHA-256 (64 hex characters)"),
                @Header(name = "X-Hash-After", description = "Output SHA-256 (64 hex characters)")
            })
    @PostMapping(value = "/unique", consumes = "multipart/form-data", produces = "video/mp4")
    public void unique(
            @RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "1.01") double speed,
            @RequestParam(defaultValue = "0.98") double scale,
            HttpServletResponse response)
            throws IOException {
        service.process(
                file,
                new ProcessingOptions(speed, scale),
                (output, before, after) -> {
                    response.setContentType("video/mp4");
                    response.setHeader("Content-Disposition", "attachment; filename=unique.mp4");
                    response.setHeader("X-Hash-Before", before);
                    response.setHeader("X-Hash-After", after);
                    response.setContentLengthLong(Files.size(output));
                    Files.copy(output, response.getOutputStream());
                    response.flushBuffer();
                });
    }
}
