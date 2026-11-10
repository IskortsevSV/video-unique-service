package io.github.iskortsevsv.video.config;

import java.nio.file.Path;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Настройки Telegram-бота; строковое представление не раскрывает токен. */
@ConfigurationProperties("telegram")
public record TelegramProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") String token,
        @DefaultValue(".local/telegram-offset.txt") Path offsetFile,
        @DefaultValue Set<Long> allowedChatIds,
        @DefaultValue("8081") int localApiPort,
        Path localApiFiles) {
    private static final long BYTES_PER_MB = 1_000_000L;

    @ConstructorBinding
    public TelegramProperties {
        allowedChatIds = allowedChatIds == null ? Set.of() : Set.copyOf(allowedChatIds);
        if (enabled && (token == null || !token.matches("[0-9]+:[A-Za-z0-9_-]+"))) {
            throw new IllegalArgumentException(
                    "Set a valid TELEGRAM_TOKEN before enabling the bot");
        }
        if (localApiFiles != null
                && (!localApiFiles.isAbsolute() || localApiPort < 1 || localApiPort > 65535)) {
            throw new IllegalArgumentException("Set an absolute Telegram file directory and a valid port");
        }
    }

    public TelegramProperties(boolean enabled, String token, Path offsetFile, Set<Long> allowedChatIds) {
        this(enabled, token, offsetFile, allowedChatIds, 8081, null);
    }

    /** Возвращает входной лимит в десятичных мегабайтах для выбранного Bot API. */
    public int maxInputMb() {
        return localApiFiles == null ? 20 : 150;
    }

    /** Проверяет размер входящего файла в байтах. */
    public void checkInputSize(long size) {
        if (size > maxInputMb() * BYTES_PER_MB) {
            throw new IllegalArgumentException(
                    "Файл превышает " + maxInputMb() + " МБ. Отправьте файл меньшего размера.");
        }
    }

    /** Проверяет транспортный лимит при отправке обработанного файла. */
    public void checkOutputSize(long size) {
        int limit = localApiFiles == null ? 50 : 2_000;
        if (size > limit * BYTES_PER_MB) {
            throw new IllegalArgumentException("Результат превышает лимит Telegram " + limit + " МБ.");
        }
    }

    /** Хранит позицию и журнал локальной очереди отдельно от облачной очереди. */
    public Path pollingOffsetFile() {
        return localApiFiles == null
                ? offsetFile
                : offsetFile.resolveSibling(offsetFile.getFileName() + ".local-api");
    }

    /** Возвращает краткое представление настроек без токена. */
    @Override
    public String toString() {
        return "TelegramProperties[enabled=" + enabled + ", token=REDACTED]";
    }
}
