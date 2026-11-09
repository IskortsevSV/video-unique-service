package io.github.iskortsevsv.video.telegram;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Локальный журнал принятых обновлений. Медиафайлы в журнале не сохраняются. */
final class BotJournal {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(BotJournal.class);
    private static final ThreadLocal<Item> CURRENT = new ThreadLocal<>();
    private final Path directory;
    private final JsonMapper mapper = new JsonMapper();
    private final Set<Long> active = ConcurrentHashMap.newKeySet();

    BotJournal(Path offset) {
        directory = offset.toAbsolutePath().resolveSibling(offset.getFileName() + ".jobs");
    }

    synchronized Item stage(JsonNode update) throws IOException {
        Files.createDirectories(directory);
        long id = update.path("update_id").asLong();
        Path path = directory.resolve(id + ".properties");
        if (Files.exists(quarantinePath(path))) {
            return blocked(id, path);
        }
        Properties p;
        if (Files.exists(path)) {
            try {
                p = validated(path);
            } catch (RuntimeException ex) {
                quarantine(path);
                return blocked(id, path);
            }
        } else {
            p = new Properties();
        }
        if (p.isEmpty()) {
            p.setProperty(
                    "update",
                    Base64.getEncoder()
                            .encodeToString(
                                    update.toString()
                                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            p.setProperty("sent", "0");
            write(path, p);
        }
        return new Item(this, id, path, p);
    }

    synchronized List<JsonNode> pending() throws IOException {
        if (!Files.exists(directory)) {
            return List.of();
        }
        List<JsonNode> updates = new ArrayList<>();
        // A previous JVM may have stopped between writing and replacing the state file.
        // Writers use the same monitor, so these files cannot belong to an active write here.
        try (var temporary = Files.list(directory)) {
            for (Path path :
                    temporary
                            .filter(p -> p.getFileName().toString().endsWith(".properties.tmp"))
                            .toList()) {
                Files.deleteIfExists(path);
            }
        }
        try (var paths = Files.list(directory)) {
            for (Path p :
                    paths.filter(x -> x.getFileName().toString().endsWith(".properties"))
                            .sorted()
                            .toList()) {
                try {
                    Properties data = validated(p);
                    if (!"true".equals(data.getProperty("done"))
                            && Long.parseLong(data.getProperty("retry-at", "0"))
                                    <= System.currentTimeMillis()) {
                        updates.add(decodeUpdate(data));
                    }
                } catch (RuntimeException ex) {
                    quarantine(p);
                }
            }
        }
        return updates;
    }

    synchronized void prune(long offset) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.list(directory)) {
            for (Path p :
                    paths.filter(x -> x.getFileName().toString().endsWith(".properties"))
                            .toList()) {
                String name = p.getFileName().toString();
                try {
                    long id =
                            Long.parseLong(
                                    name.substring(0, name.length() - ".properties".length()));
                    if (id < offset && "true".equals(validated(p).getProperty("done"))) {
                        Files.deleteIfExists(p);
                    }
                } catch (RuntimeException ex) {
                    quarantine(p);
                }
            }
        }
    }

    private Path quarantinePath(Path file) {
        return directory.resolve("quarantine").resolve(file.getFileName());
    }

    private void quarantine(Path file) throws IOException {
        Path target = quarantinePath(file);
        Files.createDirectories(target.getParent());
        // The original name also serves as a durable block against replaying an unknown delivery.
        if (Files.exists(target)) {
            target = target.resolveSibling(target.getFileName() + "." + java.util.UUID.randomUUID());
        }
        Files.move(file, target);
        LOG.warn("Telegram journal record quarantined; manual review required");
    }

    private Item blocked(long id, Path path) {
        var data = new Properties();
        data.setProperty("done", "true");
        return new Item(this, id, path, data);
    }

    private JsonNode decodeUpdate(Properties data) {
        return mapper.readTree(Base64.getDecoder().decode(data.getProperty("update")));
    }

    private Properties validated(Path path) throws IOException {
        var data = read(path);
        String name = path.getFileName().toString();
        long id = Long.parseLong(name.substring(0, name.length() - ".properties".length()));
        if (id < 0 || (data.containsKey("done") && !data.getProperty("done").equals("true"))) {
            throw new IllegalArgumentException("Invalid journal record");
        }
        if ("true".equals(data.getProperty("done"))) {
            boolean failed =
                    data.size() == 2
                            && data.containsKey("failure-code")
                            && new TelegramApiException(
                                            1, Integer.parseInt(data.getProperty("failure-code")))
                                    .permanent();
            if (data.size() != 1 && !failed) {
                throw new IllegalArgumentException("Invalid completion marker");
            }
            return data;
        }
        var update = decodeUpdate(data);
        if (update == null || !update.isObject() || !update.path("update_id").isIntegralNumber()
                || update.path("update_id").asLong() != id) {
            throw new IllegalArgumentException("Invalid update identity");
        }
        int sent = Integer.parseInt(data.getProperty("sent"));
        if (Long.parseLong(data.getProperty("retry-at", "0")) < 0) {
            throw new IllegalArgumentException("Invalid retry time");
        }
        if (sent < 0 || sent > 10 || !Set.of("true", "false").contains(data.getProperty("sending", "false"))) {
            throw new IllegalArgumentException("Invalid delivery state");
        }
        boolean snapshot = data.containsKey("mode") || data.containsKey("copies")
                || data.containsKey("speed") || data.containsKey("scale");
        if (snapshot) {
            int copies = Integer.parseInt(data.getProperty("copies"));
            if (!Set.of("basic", "hard").contains(data.getProperty("mode")) || copies < 1 || copies > 10 || sent > copies) {
                throw new IllegalArgumentException("Invalid job settings");
            }
            new io.github.iskortsevsv.video.dto.ProcessingOptions(
                    Double.parseDouble(data.getProperty("speed")), Double.parseDouble(data.getProperty("scale")));
        } else if (sent != 0 || Boolean.parseBoolean(data.getProperty("sending"))) {
            throw new IllegalArgumentException("Missing job settings");
        }
        return data;
    }

    private static Properties read(Path path) throws IOException {
        Properties p = new Properties();
        if (Files.exists(path)) {
            try (var in = Files.newInputStream(path)) {
                p.load(in);
            }
        }
        return p;
    }

    private static void write(Path path, Properties p) throws IOException {
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try (var channel =
                FileChannel.open(
                        tmp,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE)) {
            var out = java.nio.channels.Channels.newOutputStream(channel);
            p.store(out, "VUniq pending update");
            out.flush();
            channel.force(true);
        }
        try {
            Files.move(
                    tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static Item current() {
        return CURRENT.get();
    }

    static void set(Item item) {
        if (item == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(item);
        }
    }

    /** Хранит и атомарно обновляет состояние одного задания журнала. */
    static final class Item {
        private final BotJournal owner;
        private final long id;
        private final Path path;
        private final Properties data;
        private volatile boolean scheduled;

        Item(BotJournal owner, long id, Path path, Properties data) {
            this.owner = owner;
            this.id = id;
            this.path = path;
            this.data = data;
        }

        boolean done() {
            return "true".equals(data.getProperty("done"));
        }

        boolean active() {
            return owner.active.contains(id);
        }

        synchronized boolean ready() {
            return Long.parseLong(data.getProperty("retry-at", "0")) <= System.currentTimeMillis();
        }

        /** Сохраняет отказ либо время повтора, не сбрасывая состояние доставки файла. */
        synchronized void failed(TelegramApiException error) throws IOException {
            if (error.permanent()) {
                data.clear();
                data.setProperty("done", "true");
                data.setProperty("failure-code", Integer.toString(error.errorCode()));
            } else {
                data.setProperty(
                        "retry-at",
                        Long.toString(System.currentTimeMillis() + error.retrySeconds() * 1000L));
            }
            persist();
        }

        void scheduled() {
            scheduled = true;
            owner.active.add(id);
        }

        void rejected() {
            scheduled = false;
            owner.active.remove(id);
        }

        void finishDispatch() throws IOException {
            if (!scheduled) {
                complete();
            }
        }

        void finishWorker() {
            owner.active.remove(id);
        }

        synchronized Snapshot snapshot(
                BotUserStore.Profile profile,
                io.github.iskortsevsv.video.dto.ProcessingOptions options)
                throws IOException {
            if (!data.containsKey("mode")) {
                data.setProperty("mode", profile.mode());
                data.setProperty("copies", Integer.toString(profile.copies()));
                data.setProperty("speed", Double.toString(options.speed()));
                data.setProperty("scale", Double.toString(options.scale()));
                persist();
            }
            return new Snapshot(
                    data.getProperty("mode"),
                    Integer.parseInt(data.getProperty("copies")),
                    Double.parseDouble(data.getProperty("speed")),
                    Double.parseDouble(data.getProperty("scale")));
        }

        synchronized int sent() {
            return Integer.parseInt(data.getProperty("sent", "0"));
        }

        synchronized boolean uncertain() {
            return "true".equals(data.getProperty("sending"));
        }

        synchronized void sending(boolean value) throws IOException {
            data.setProperty("sending", Boolean.toString(value));
            persist();
        }

        synchronized void delivered(int count) throws IOException {
            data.setProperty("sent", Integer.toString(count));
            data.setProperty("sending", "false");
            persist();
        }

        synchronized void complete() throws IOException {
            data.clear();
            data.setProperty("done", "true");
            persist();
        }

        private void persist() throws IOException {
            synchronized (owner) {
                write(path, data);
            }
        }
    }

    /** Фиксирует параметры обработки, выбранные при приёме задания. */
    record Snapshot(String mode, int copies, double speed, double scale) {}

    static Snapshot snapshot(
            BotUserStore.Profile profile, io.github.iskortsevsv.video.dto.ProcessingOptions options)
            throws IOException {
        return current() == null
                ? new Snapshot(profile.mode(), profile.copies(), options.speed(), options.scale())
                : current().snapshot(profile, options);
    }

    static void sending(boolean value) throws IOException {
        if (current() != null) {
            current().sending(value);
        }
    }

    static void delivered(int count) throws IOException {
        if (current() != null) {
            current().delivered(count);
        }
    }

    static int sent() {
        return current() == null ? 0 : current().sent();
    }

    static boolean uncertain() {
        return current() != null && current().uncertain();
    }
}
