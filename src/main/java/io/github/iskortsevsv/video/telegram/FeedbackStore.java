package io.github.iskortsevsv.video.telegram;

import io.github.iskortsevsv.video.config.TelegramProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/** Хранит выдачу копий и их текущие оценки независимо от очереди обработки. */
@Component
@ConditionalOnProperty(name = "telegram.enabled", havingValue = "true")
class FeedbackStore {
    private final Path root;

    FeedbackStore(TelegramProperties config) {
        root = config.offsetFile().toAbsolutePath().resolveSibling("feedback");
    }

    synchronized String prepare(long chat, long sourceMessage, int copy, boolean photo,
            String mode, String version) throws IOException {
        String id;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(
                    (chat + "/" + sourceMessage + "/" + copy).getBytes(StandardCharsets.US_ASCII));
            id = HexFormat.of().formatHex(hash, 0, 16);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
        Path path = file(id);
        if (!Files.exists(path)) {
            var row = new Properties();
            row.setProperty("chat", Long.toString(chat));
            row.setProperty("media", photo ? "Фото" : "Видео");
            row.setProperty("mode", mode);
            row.setProperty("version", version);
            row.setProperty("copy", Integer.toString(copy));
            TelegramStateFiles.write(path, row);
        }
        return id;
    }

    synchronized Properties owned(String id, long chat) throws IOException {
        var row = TelegramStateFiles.read(file(id));
        if (!Long.toString(chat).equals(row.getProperty("chat"))) {
            throw new IllegalArgumentException("Эта копия недоступна.");
        }
        return row;
    }

    synchronized void delivered(String id, long chat) throws IOException {
        var row = owned(id, chat);
        if (!row.containsKey("delivered")) {
            row.setProperty("delivered", Instant.now().toString());
            TelegramStateFiles.write(file(id), row);
        }
    }

    synchronized void vote(String id, long chat, String result, String format, String previous)
            throws IOException {
        var row = owned(id, chat);
        row.setProperty("result", result);
        row.setProperty("format", format);
        row.setProperty("previous", previous);
        TelegramStateFiles.write(file(id), row);
    }

    java.util.List<String> stats(Instant now) throws IOException {
        Map<String, Counts> groups = new TreeMap<>();
        Map<String, Counts> formats = new TreeMap<>();
        if (Files.isDirectory(root)) {
            try (var files = Files.newDirectoryStream(root, "*.properties")) {
                for (Path path : files) {
                    var row = TelegramStateFiles.read(path);
                    String delivered = row.getProperty("delivered");
                    if (delivered == null || Instant.parse(delivered).isBefore(now.minus(30, ChronoUnit.DAYS))) {
                        continue;
                    }
                    String group = row.getProperty("media") + " · " + row.getProperty("mode")
                            + " · " + row.getProperty("version");
                    String result = row.getProperty("result", "");
                    groups.computeIfAbsent(group, ignored -> new Counts()).add(result);
                    String format = row.getProperty("format", "");
                    if (!format.isEmpty()) {
                        formats.computeIfAbsent(group + " · " + format, ignored -> new Counts()).add(result);
                    }
                }
            }
        }
        var lines = new java.util.ArrayList<String>();
        groups.forEach((key, value) -> lines.add(key + "\n" + value.summary(true)));
        if (!formats.isEmpty()) {
            lines.add("По форматам проверенных публикаций");
            formats.forEach((key, value) -> lines.add(key + "\n" + value.summary(false)));
        }
        return lines;
    }

    private Path file(String id) {
        if (!id.matches("[a-f0-9]{32}")) {
            throw new IllegalArgumentException("Эта копия недоступна.");
        }
        return root.resolve(id + ".properties");
    }

    private static final class Counts {
        private int issued, untouched, passed, duplicate, other;

        void add(String result) {
            issued++;
            switch (result) {
                case "wait" -> untouched++;
                case "ok" -> passed++;
                case "dup" -> duplicate++;
                case "err" -> other++;
                default -> { }
            }
        }

        String summary(boolean total) {
            int tested = passed + duplicate + other;
            int answered = tested + untouched;
            String coverage = total
                    ? "Выдано " + issued + ", ответили " + answered + "/" + issued
                        + " (" + Math.round(100.0 * answered / issued) + "%), не проверяли "
                        + untouched + ", без ответа " + (issued - answered) + ". "
                    : "";
            return coverage + "Проверено " + tested + ", без предупреждения " + passed
                    + ", повторов " + duplicate + "/" + (passed + duplicate)
                    + ", других ошибок " + other + ".";
        }
    }
}
