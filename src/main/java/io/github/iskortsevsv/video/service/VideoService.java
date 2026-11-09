package io.github.iskortsevsv.video.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.Semaphore;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import io.github.iskortsevsv.video.config.VideoProperties;
import io.github.iskortsevsv.video.dto.ProcessingOptions;
import io.github.iskortsevsv.video.exception.VideoException;

/** Ограничивает параллелизм, управляет временными файлами и обработкой видео. */
@Service
public class VideoService {
    private final VideoProcessor processor;
    private final HashService hashes;
    private final Semaphore slots;

    public VideoService(VideoProcessor processor, HashService hashes, VideoProperties config) {
        this.processor = processor;
        this.hashes = hashes;
        this.slots = new Semaphore(config.maxConcurrent());
    }

    /** Получает результат обработки и хеши до удаления рабочего каталога. */
    @FunctionalInterface
    public interface ResultConsumer {
        /**
         * Принимает результат, доступный только до завершения обработки.
         *
         * @param output путь к обработанному файлу
         * @param before SHA-256 исходного файла
         * @param after SHA-256 результата
         * @throws IOException если не удалось передать результат
         */
        void accept(Path output, String before, String after) throws IOException;
    }

    /** Записывает исходный файл во временный каталог. */
    @FunctionalInterface
    public interface InputWriter {
        /**
         * Записывает исходные данные по указанному пути.
         *
         * @param input путь файла во временном каталоге
         * @throws IOException если запись завершилась ошибкой
         */
        void write(Path input) throws IOException;
    }

    /** Обрабатывает исходный файл в пределах жизненного цикла временного каталога. */
    @FunctionalInterface
    public interface InputConsumer {
        /**
         * Обрабатывает входной файл внутри временного каталога.
         *
         * @param input путь к загруженному файлу
         * @param workspace каталог, который будет удалён после обработки
         * @throws IOException если обработка файлов завершилась ошибкой
         */
        void accept(Path input, TemporaryWorkspace workspace) throws IOException;
    }

    /** Обрабатывает входное видео и передаёт результат обработчику до удаления временных файлов. */
    public void process(MultipartFile file, ProcessingOptions options, ResultConsumer consumer)
            throws IOException {
        if (file.isEmpty()) {
            throw new VideoException(HttpStatus.BAD_REQUEST, "A non-empty file is required");
        }
        process(file.getOriginalFilename(), file::transferTo, options, consumer);
    }

    /** Обрабатывает входное видео и передаёт результат обработчику до удаления временных файлов. */
    public void process(
            String name, InputWriter writer, ProcessingOptions options, ResultConsumer consumer)
            throws IOException {
        if (name == null
                || !(name.toLowerCase(Locale.ROOT).endsWith(".mp4")
                        || name.toLowerCase(Locale.ROOT).endsWith(".mov"))) {
            throw new VideoException(
                    HttpStatus.BAD_REQUEST, "A non-empty .mp4 or .mov file is required");
        }
        withInput(
                writer,
                (input, workspace) -> {
                    Path output = workspace.file("output.mp4");
                    String before = hashes.sha256(input);
                    processor.process(input, output, options);
                    consumer.accept(output, before, hashes.sha256(output));
                });
    }

    /**
     * Создаёт изолированный временный каталог, записывает исходник и вызывает обработчик.
     * Каталог и слот параллелизма освобождаются после выхода из обработчика.
     *
     * @param writer загрузчик исходного файла
     * @param consumer обработчик входного файла и рабочего каталога
     * @throws IOException если запись или удаление временных файлов завершились ошибкой
     * @throws VideoException если все слоты заняты или загружен пустой файл
     */
    public void withInput(InputWriter writer, InputConsumer consumer) throws IOException {
        if (!slots.tryAcquire()) {
            throw new VideoException(
                    HttpStatus.TOO_MANY_REQUESTS, "All processing slots are busy; retry later");
        }
        try (var workspace = new TemporaryWorkspace()) {
            Path input = workspace.file("input.mp4");
            writer.write(input);
            if (Files.size(input) == 0) {
                throw new VideoException(HttpStatus.BAD_REQUEST, "A non-empty file is required");
            }
            consumer.accept(input, workspace);
        } finally {
            slots.release();
        }
    }
}
