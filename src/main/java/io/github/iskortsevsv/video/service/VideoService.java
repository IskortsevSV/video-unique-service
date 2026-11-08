package io.github.iskortsevsv.video.service;

import io.github.iskortsevsv.video.config.VideoProperties;
import io.github.iskortsevsv.video.dto.ProcessingOptions;
import io.github.iskortsevsv.video.exception.VideoException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.file.*;
import java.util.Locale;
import java.util.concurrent.Semaphore;

@Service
public class VideoService {
    private final VideoProcessor processor;
    private final HashService hashes;
    private final Semaphore slots;
    public VideoService(VideoProcessor processor, HashService hashes, VideoProperties config) {
        this.processor = processor; this.hashes = hashes; this.slots = new Semaphore(config.maxConcurrent());
    }
    @FunctionalInterface public interface ResultConsumer {
        void accept(Path output, String before, String after) throws IOException;
    }
    @FunctionalInterface public interface InputWriter {
        void write(Path input) throws IOException;
    }
    public void process(MultipartFile file, ProcessingOptions options, ResultConsumer consumer) throws IOException {
        if (file.isEmpty()) throw new VideoException(HttpStatus.BAD_REQUEST, "A non-empty file is required");
        process(file.getOriginalFilename(), file::transferTo, options, consumer);
    }
    public void process(String name, InputWriter writer, ProcessingOptions options, ResultConsumer consumer) throws IOException {
        if (name == null || !(name.toLowerCase(Locale.ROOT).endsWith(".mp4") || name.toLowerCase(Locale.ROOT).endsWith(".mov"))) {
            throw new VideoException(HttpStatus.BAD_REQUEST, "A non-empty .mp4 or .mov file is required");
        }
        if (!slots.tryAcquire()) throw new VideoException(HttpStatus.TOO_MANY_REQUESTS, "All processing slots are busy; retry later");
        try (var workspace = new TemporaryWorkspace()) {
            Path input = workspace.file("input.mp4");
            Path output = workspace.file("output.mp4");
            writer.write(input);
            if (Files.size(input) == 0) throw new VideoException(HttpStatus.BAD_REQUEST, "A non-empty file is required");
            String before = hashes.sha256(input);
            processor.process(input, output, options);
            consumer.accept(output, before, hashes.sha256(output));
        } finally { slots.release(); }
    }
}
