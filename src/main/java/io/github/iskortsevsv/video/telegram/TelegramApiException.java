package io.github.iskortsevsv.video.telegram;

/** Ошибка Telegram API с ограниченным интервалом повторной попытки. */
public final class TelegramApiException extends RuntimeException {
    private final int retrySeconds;

    public TelegramApiException(int retrySeconds) {
        super("Telegram request failed"); // Do not retain exceptions whose URLs contain a bot
        // token.
        this.retrySeconds = Math.max(5, Math.min(300, retrySeconds));
    }

    /** Возвращает ограниченный интервал ожидания перед повтором в секундах. */
    public int retrySeconds() {
        return retrySeconds;
    }
}
