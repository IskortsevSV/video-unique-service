package io.github.iskortsevsv.video.telegram;

/** Ошибка Telegram API с ограниченным интервалом повторной попытки. */
public final class TelegramApiException extends RuntimeException {
    private final int retrySeconds;
    private final boolean rateLimited;

    public TelegramApiException(int retrySeconds) {
        this(retrySeconds, false);
    }

    public TelegramApiException(int retrySeconds, boolean rateLimited) {
        super("Telegram request failed"); // Do not retain exceptions whose URLs contain a bot
        // token.
        this.retrySeconds = Math.max(1, retrySeconds);
        this.rateLimited = rateLimited;
    }

    /** Показывает, что Telegram явно отклонил запрос из-за ограничения частоты. */
    public boolean rateLimited() {
        return rateLimited;
    }

    /** Возвращает ограниченный интервал ожидания перед повтором в секундах. */
    public int retrySeconds() {
        return retrySeconds;
    }
}
