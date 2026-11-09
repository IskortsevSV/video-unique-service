package io.github.iskortsevsv.video.telegram;

/** Ошибка Telegram API с ограниченным интервалом повторной попытки. */
public final class TelegramApiException extends RuntimeException {
    private final int retrySeconds;
    private final boolean rateLimited;
    private final int errorCode;

    public TelegramApiException(int retrySeconds) {
        this(retrySeconds, false);
    }

    public TelegramApiException(int retrySeconds, boolean rateLimited) {
        this(retrySeconds, rateLimited ? 429 : 0);
    }

    /** Создаёт безопасную ошибку без тела ответа и URL, содержащего токен. */
    public TelegramApiException(int retrySeconds, int errorCode) {
        super("Telegram request failed"); // Do not retain exceptions whose URLs contain a bot
        // token.
        this.retrySeconds = Math.max(1, retrySeconds);
        this.errorCode = errorCode;
        this.rateLimited = errorCode == 429;
    }

    /** Возвращает код Telegram/HTTP; ноль означает неопределённый результат сетевого запроса. */
    public int errorCode() {
        return errorCode;
    }

    /** Показывает, что повтор неизменного запроса для этого задания не исправит отказ. */
    public boolean permanent() {
        return switch (errorCode) {
            case 400, 403, 404, 410, 413, 414, 415, 422 -> true;
            default -> false;
        };
    }

    /** Ошибки токена, конфликт polling и rate limit требуют общей паузы. */
    public boolean global() {
        return errorCode == 401 || errorCode == 409 || rateLimited;
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
