package io.github.iskortsevsv.video.telegram;

import io.github.iskortsevsv.video.config.TelegramProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Сохраняет только добровольно отправленные материалы и удаляет их через семь дней. */
@Component
@ConditionalOnProperty(name = "telegram.enabled", havingValue = "true")
class MaterialArchive {
    private static final long CAPACITY = 2_000_000_000L;
    private static final String STAGED = "incoming.tmp";
    private final Path root;
    private final TelegramProperties config;
    private final ReferenceInspector inspector;
    private ScheduledExecutorService timer;

    MaterialArchive(TelegramProperties config, ReferenceInspector inspector) {
        this.config = config;
        this.inspector = inspector;
        root = config.offsetFile().toAbsolutePath().resolveSibling("feedback-materials");
    }

    enum Role {
        SCREENSHOT("Скриншот"), SOURCE("Исходник"), COPY("Обработанная копия");

        final String label;

        Role(String label) {
            this.label = label;
        }

        String filename() {
            return name().toLowerCase(java.util.Locale.ROOT) + ".bin";
        }
    }

    @PostConstruct
    void start() {
        if (config.feedbackUploadsEnabled() || config.referenceEnabled() || Files.isDirectory(root)) {
            sweepSafely();
            timer = Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon().name("material-archive-cleaner").factory());
            timer.scheduleWithFixedDelay(this::sweepSafely, 1, 1, TimeUnit.HOURS);
        }
    }

    @PreDestroy
    void stop() {
        if (timer != null) {
            timer.shutdownNow();
        }
    }

    synchronized void open(String id, long chat) throws IOException {
        Path folder = folder(id);
        if (!Files.exists(root) && root.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.createDirectories(root, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
        }
        var owner = TelegramStateFiles.read(folder.resolve("owner.properties"));
        if (!owner.isEmpty()) {
            requireOwner(id, chat);
        } else {
            owner.setProperty("chat", Long.toString(chat));
            TelegramStateFiles.write(folder.resolve("owner.properties"), owner);
        }
    }

    synchronized void requireOwner(String id, long chat) throws IOException {
        if (!Long.toString(chat).equals(
                TelegramStateFiles.read(folder(id).resolve("owner.properties")).getProperty("chat"))) {
            throw new IllegalArgumentException("Это обращение недоступно.");
        }
    }

    synchronized String add(String id, long chat, Role role, Path incoming, Instant now)
            throws IOException {
        requireOwner(id, chat);
        Path folder = folder(id);
        Path target = folder.resolve(role.filename());
        long size = Files.size(incoming);
        config.checkInputSize(size);
        if (size == 0) {
            throw new IllegalArgumentException("Файл пуст.");
        }
        sweep(now);
        if (storedBytes() + size > CAPACITY) {
            throw new IllegalArgumentException("Архив заполнен. Удалите старые материалы или попробуйте позже.");
        }
        var current = inspector.inspect(incoming);
        if (role == Role.SCREENSHOT && !current.photo()) {
            throw new IllegalArgumentException("Скриншот должен быть в JPEG или PNG.");
        }
        ReferenceInspector.Info other = null;
        if (role != Role.SCREENSHOT) {
            Path comparison = folder.resolve((role == Role.SOURCE ? Role.COPY : Role.SOURCE).filename());
            if (Files.isRegularFile(comparison)) {
                other = inspector.inspect(comparison);
                if (current.photo() != other.photo()) {
                    throw new IllegalArgumentException("Исходник и копия должны быть одного типа.");
                }
            }
        }
        Path staged = folder.resolve(STAGED);
        try {
            Files.copy(incoming, staged);
            Files.setLastModifiedTime(staged, FileTime.from(now));
            TelegramStateFiles.replace(staged, target);
        } finally {
            Files.deleteIfExists(staged);
        }

        if (!id.startsWith("r")) {
            return role.label + " сохранён на 7 дней.";
        }
        String note = "\n\nЭто параметры файла. Они не раскрывают алгоритм обработки.";
        if (other == null) {
            return role.label + current.summary(null) + note;
        }
        return "Параметры пары · исходник → копия"
                + (role == Role.SOURCE ? other.summary(current) : current.summary(other)) + note;
    }

    synchronized void delete(String id, long chat) throws IOException {
        requireOwner(id, chat);
        Path folder = folder(id);
        for (Role role : Role.values()) {
            Files.deleteIfExists(folder.resolve(role.filename()));
        }
        Files.deleteIfExists(folder.resolve(STAGED));
        Files.deleteIfExists(folder.resolve("owner.properties.tmp"));
        Files.deleteIfExists(folder.resolve("owner.properties"));
        Files.deleteIfExists(folder);
    }

    synchronized void sweep(Instant now) throws IOException {
        if (!Files.isDirectory(root)) {
            return;
        }
        Instant deadline = now.minus(7, ChronoUnit.DAYS);
        try (var cases = Files.newDirectoryStream(root, "[fr]*")) {
            for (Path folder : cases) {
                if (!folder.getFileName().toString().matches("[fr][a-f0-9]{32}")
                        || !Files.isDirectory(folder, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                Files.deleteIfExists(folder.resolve(STAGED));
                for (Role role : Role.values()) {
                    Path file = folder.resolve(role.filename());
                    if (Files.isRegularFile(file) && !Files.getLastModifiedTime(file).toInstant().isAfter(deadline)) {
                        Files.delete(file);
                    }
                }
            }
        }
    }

    private long storedBytes() throws IOException {
        try (var files = Files.find(root, 2, (path, attributes) -> attributes.isRegularFile()
                && path.getFileName().toString().endsWith(".bin"))) {
            long size = 0;
            for (Path file : (Iterable<Path>) files::iterator) {
                size += Files.size(file);
            }
            return size;
        }
    }

    private Path folder(String id) {
        if (!id.matches("[fr][a-f0-9]{32}")) {
            throw new IllegalArgumentException("Это обращение недоступно.");
        }
        return root.resolve(id);
    }

    private void sweepSafely() {
        try {
            sweep(Instant.now());
        } catch (IOException ex) {
            LoggerFactory.getLogger(MaterialArchive.class).warn("Could not clean expired feedback materials");
        }
    }
}
