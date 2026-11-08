package io.github.iskortsevsv.video.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import io.github.iskortsevsv.video.exception.VideoException;

/** Запускает внешний процесс, вычитывает вывод и контролирует таймаут. */
@Component
public class ProcessRunner {
    /**
     * Выполняет команду без shell и возвращает не более 64 КиБ объединённого вывода.
     *
     * @param command исполняемый файл и его аргументы
     * @param timeout максимальное время работы процесса
     * @return начало объединённого вывода процесса в UTF-8
     * @throws VideoException если запуск, выполнение или ожидание процесса завершились ошибкой
     */
    public String run(List<String> command, Duration timeout) {
        return run(command, timeout, line -> {});
    }

    /**
     * Выполняет команду и передаёт строки объединённого вывода обработчику.
     * Обработчик вызывается из потока чтения и не должен надолго блокироваться.
     *
     * @param command исполняемый файл и его аргументы
     * @param timeout максимальное время работы процесса
     * @param lines обработчик строк вывода
     * @return начало объединённого вывода, не более 64 КиБ в UTF-8
     * @throws VideoException если запуск, выполнение или ожидание процесса завершились ошибкой
     */
    public String run(
            List<String> command, Duration timeout, java.util.function.Consumer<String> lines) {
        WindowsProcessJob.ensure();
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.getOutputStream().close();
        } catch (IOException ex) {
            throw new VideoException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Media executable unavailable");
        }
        // Drain continuously, retaining only a bounded diagnostic/probe prefix.
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var output =
                    executor.submit(
                            () -> {
                                try (var input = process.getInputStream();
                                        var buffer = new ByteArrayOutputStream()) {
                                    byte[] chunk = new byte[8192];
                                    var line = new StringBuilder();
                                    int count;
                                    while ((count = input.read(chunk)) != -1) {
                                        buffer.write(
                                                chunk,
                                                0,
                                                Math.min(
                                                        count, Math.max(0, 65536 - buffer.size())));
                                        for (int i = 0; i < count; i++) {
                                            if (chunk[i] == '\n') {
                                                lines.accept(line.toString().strip());
                                                line.setLength(0);
                                            } else if (line.length() < 4096) {
                                                line.append((char) chunk[i]);
                                            }
                                        }
                                    }
                                    return buffer.toString(StandardCharsets.UTF_8);
                                }
                            });
            try {
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new VideoException(
                            HttpStatus.GATEWAY_TIMEOUT, "Media processing timed out");
                }
                String result = output.get(5, TimeUnit.SECONDS);
                if (process.exitValue() != 0) {
                    throw new VideoException(
                            HttpStatus.UNPROCESSABLE_CONTENT, "Invalid or unsupported video");
                }
                return result;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new VideoException(HttpStatus.SERVICE_UNAVAILABLE, "Processing interrupted");
            } catch (ExecutionException | TimeoutException ex) {
                throw new VideoException(
                        HttpStatus.INTERNAL_SERVER_ERROR, "Cannot read media process output");
            } finally {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                try {
                    process.waitFor();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
