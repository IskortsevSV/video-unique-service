package io.github.iskortsevsv.video.telegram;

/** Определяет доступное число копий с одного исходника независимо от режима обработки. */
enum CopyAccess {
    BASIC("Базовый", 1, 3),
    HIGH("High", 3, 10);

    final String label;
    final int videos;
    final int photos;

    CopyAccess(String label, int videos, int photos) {
        this.label = label;
        this.videos = videos;
        this.photos = photos;
    }

    int limit(boolean photo) {
        return photo ? photos : videos;
    }

    int selected(BotUserStore.Profile profile, boolean photo) {
        return Math.min(photo ? profile.photoCopies() : profile.videoCopies(), limit(photo));
    }

    void validate(boolean photo, int count) {
        if (count < 1 || count > limit(photo)) {
            throw new IllegalArgumentException("Допустимое число копий: от 1 до " + limit(photo));
        }
    }
}
