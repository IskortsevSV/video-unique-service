package io.github.iskortsevsv.video.telegram;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Properties;

import org.springframework.stereotype.Component;

import io.github.iskortsevsv.video.config.TelegramProperties;

/** Локально хранит настройки пользователей и месячную статистику доставленных копий. */
@Component
public class BotUserStore {
    /** Настройки обработки и счётчики за указанный календарный месяц. */
    public record Profile(String mode, int copies, int originals, int results, YearMonth month) {
        public Profile {
            if (!mode.equals("basic") && !mode.equals("hard")) {
                throw new IllegalArgumentException("Unknown mode");
            }
            if (copies < 1 || copies > 10) {
                throw new IllegalArgumentException("Copies must be between 1 and 10");
            }
        }

        /** Показывает, выбран ли усиленный режим обработки. */
        public boolean hard() {
            return mode.equals("hard");
        }
    }

    private final Path root;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public BotUserStore(TelegramProperties config) {
        this(config, Clock.system(ZoneId.of("Europe/Moscow")));
    }

    BotUserStore(TelegramProperties config, Clock clock) {
        this.root = config.offsetFile().toAbsolutePath().resolveSibling("users");
        this.clock = clock;
    }

    /** Читает профиль пользователя и возвращает счётчики текущего месяца. */
    public synchronized Profile get(long id) throws IOException {
        var data = new Properties();
        Path file = root.resolve(id + ".properties");
        if (Files.exists(file)) {
            try (var input = Files.newInputStream(file)) {
                data.load(input);
            }
        }
        var month = YearMonth.now(clock);
        boolean current = month.toString().equals(data.getProperty("month"));
        return new Profile(
                data.getProperty("mode", "basic"),
                Integer.parseInt(data.getProperty("copies", "3")),
                current ? Integer.parseInt(data.getProperty("originals", "0")) : 0,
                current ? Integer.parseInt(data.getProperty("results", "0")) : 0,
                month);
    }

    /** Сохраняет режим и количество копий, сохраняя текущие счётчики. */
    public synchronized Profile settings(long id, String mode, int copies) throws IOException {
        var current = get(id);
        var updated =
                new Profile(mode, copies, current.originals(), current.results(), current.month());
        save(id, updated);
        return updated;
    }

    /** Увеличивает счётчик доставленных копий и при необходимости исходников. */
    public synchronized void delivered(long id, boolean first) throws IOException {
        var p = get(id);
        save(
                id,
                new Profile(
                        p.mode(),
                        p.copies(),
                        p.originals() + (first ? 1 : 0),
                        p.results() + 1,
                        p.month()));
    }

    private void save(long id, Profile profile) throws IOException {
        Files.createDirectories(root);
        var p = new Properties();
        p.setProperty("mode", profile.mode());
        p.setProperty("copies", "" + profile.copies());
        p.setProperty("originals", "" + profile.originals());
        p.setProperty("results", "" + profile.results());
        p.setProperty("month", profile.month().toString());
        Path target = root.resolve(id + ".properties"), temporary = root.resolve(id + ".tmp");
        try (var output = Files.newOutputStream(temporary)) {
            p.store(output, "VUniq settings and monthly statistics");
        }
        try {
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
