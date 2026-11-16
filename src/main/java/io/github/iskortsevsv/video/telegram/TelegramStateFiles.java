package io.github.iskortsevsv.video.telegram;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Properties;

/** Читает и атомарно заменяет небольшие записи состояния бота. */
final class TelegramStateFiles {
    private TelegramStateFiles() {}

    static Properties read(Path path) throws IOException {
        var data = new Properties();
        if (Files.exists(path)) {
            try (var input = Files.newInputStream(path)) {
                data.load(input);
            }
        }
        return data;
    }

    static void write(Path path, Properties data) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try (var channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            var output = Channels.newOutputStream(channel);
            data.store(output, "VUniq state");
            output.flush();
            channel.force(true);
        }
        replace(temporary, path);
    }

    static void replace(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
