package io.github.iskortsevsv.video.config;

import java.nio.file.Path;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Настройки Telegram-бота; строковое представление не раскрывает токен. */
@ConfigurationProperties("telegram")
public record TelegramProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") String token,
        @DefaultValue(".local/telegram-offset.txt") Path offsetFile,
        @DefaultValue Set<Long> allowedChatIds) {
    public TelegramProperties {
        allowedChatIds = allowedChatIds == null ? Set.of() : Set.copyOf(allowedChatIds);
        if (enabled && (token == null || !token.matches("[0-9]+:[A-Za-z0-9_-]+"))) {
            throw new IllegalArgumentException(
                    "Set a valid TELEGRAM_TOKEN before enabling the bot");
        }
    }

    /** Возвращает краткое представление настроек без токена. */
    @Override
    public String toString() {
        return "TelegramProperties[enabled=" + enabled + ", token=REDACTED]";
    }
}
