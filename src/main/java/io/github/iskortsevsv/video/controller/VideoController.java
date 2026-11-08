package io.github.iskortsevsv.video.controller;

import io.github.iskortsevsv.video.dto.ProcessingOptions;
import io.github.iskortsevsv.video.service.VideoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.*;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.file.Files;

@RestController
@RequestMapping("/api/videos")
public class VideoController {
    private final VideoService service;
    public VideoController(VideoService service) { this.service = service; }
    @Operation(summary = "Re-encode a video with speed and scale adjustments",
            description = "SHA-256 indicates byte differences only, not uniqueness on social platforms. MOV inputs are also accepted.")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "video/mp4", schema = @Schema(type = "string", format = "binary")),
            headers = {@Header(name = "X-Hash-Before", description = "Original SHA-256 (64 hex characters)"),
                    @Header(name = "X-Hash-After", description = "Output SHA-256 (64 hex characters)")})
    @PostMapping(value = "/unique", consumes = "multipart/form-data", produces = "video/mp4")
    public void unique(@RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "1.01") double speed,
            @RequestParam(defaultValue = "0.98") double scale, HttpServletResponse response) throws IOException {
        service.process(file, new ProcessingOptions(speed, scale), (output, before, after) -> {
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
