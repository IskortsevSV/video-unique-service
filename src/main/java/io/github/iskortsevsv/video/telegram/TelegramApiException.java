package io.github.iskortsevsv.video.telegram;

public final class TelegramApiException extends RuntimeException {
    private final int retrySeconds;
    public TelegramApiException(int retrySeconds) {
        super("Telegram request failed"); // Do not retain exceptions whose URLs contain a bot token.
        this.retrySeconds = Math.max(5, Math.min(300, retrySeconds));
    }
    public int retrySeconds() { return retrySeconds; }
}
