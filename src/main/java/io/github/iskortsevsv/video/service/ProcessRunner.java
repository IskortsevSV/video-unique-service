package io.github.iskortsevsv.video.service;

import io.github.iskortsevsv.video.exception.VideoException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;

@Component
public class ProcessRunner {
    public String run(List<String> command, Duration timeout) {
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.getOutputStream().close();
        } catch (IOException ex) {
            throw new VideoException(HttpStatus.SERVICE_UNAVAILABLE, "Media executable unavailable");
        }
        // Drain continuously, retaining only a bounded diagnostic/probe prefix.
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var output = executor.submit(() -> {
                try (var input = process.getInputStream(); var buffer = new ByteArrayOutputStream()) {
                    byte[] chunk = new byte[8192];
                    int count;
                    while ((count = input.read(chunk)) != -1) {
                        buffer.write(chunk, 0, Math.min(count, Math.max(0, 65536 - buffer.size())));
                    }
                    return buffer.toString(StandardCharsets.UTF_8);
                }
            });
            try {
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new VideoException(HttpStatus.GATEWAY_TIMEOUT, "Media processing timed out");
                }
                String result = output.get(5, TimeUnit.SECONDS);
                if (process.exitValue() != 0) {
                    throw new VideoException(HttpStatus.UNPROCESSABLE_CONTENT, "Invalid or unsupported video");
                }
                return result;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new VideoException(HttpStatus.SERVICE_UNAVAILABLE, "Processing interrupted");
            } catch (ExecutionException | TimeoutException ex) {
                throw new VideoException(HttpStatus.INTERNAL_SERVER_ERROR, "Cannot read media process output");
            } finally {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                try { process.waitFor(); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            }
        }
    }
}
