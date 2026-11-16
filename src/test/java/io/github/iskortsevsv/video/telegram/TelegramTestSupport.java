package io.github.iskortsevsv.video.telegram;

import io.github.iskortsevsv.video.config.TelegramProperties;

/** Собирает обработчик со штатными компонентами обратной связи для тестов бота. */
final class TelegramTestSupport {
    private TelegramTestSupport() {}

    static TelegramHandler handler(TelegramClient client, TelegramProperties config,
            BotUserStore users, BotJobs jobs, MediaBatchService batches) {
        var store = new FeedbackStore(config);
        return new TelegramHandler(client, config, users, jobs, batches,
                new TelegramFeedback(client, config, store),
                new TelegramMaterials(client, config, store, new MaterialArchive(config, null), jobs));
    }
}
