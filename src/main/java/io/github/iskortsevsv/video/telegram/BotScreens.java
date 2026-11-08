package io.github.iskortsevsv.video.telegram;

import java.util.*;
import java.time.format.DateTimeFormatter;

public final class BotScreens {
    private BotScreens() {}
    static Map<String, String> button(String text, String data) { return Map.of("text", text, "callback_data", data); }
    public static Object homeKeys() {
        return Map.of("inline_keyboard", List.of(
                List.of(button("🎬 Обработать файл", "upload")),
                List.of(button("⚙️ Настройки", "settings"), button("💎 Подписка", "subscription")),
                List.of(button("📖 Как это работает", "help"))));
    }
    static Object backKeys() { return Map.of("inline_keyboard", List.of(List.of(button("← Главное меню", "home")))); }
    static Object settingsKeys(BotUserStore.Profile p) {
        var rows = new ArrayList<List<Map<String, String>>>();
        rows.add(List.of(button((p.hard() ? "" : "✓ ") + "✨ Basic", "mode:basic"), button((p.hard() ? "✓ " : "") + "🔥 Hard", "mode:hard")));
        for (int first : List.of(1, 6)) {
            var row = new ArrayList<Map<String, String>>();
            for (int i = first; i < first + 5; i++) row.add(button((p.copies() == i ? "✓ " : "") + i, "copies:" + i));
            rows.add(row);
        }
        rows.add(List.of(button("← Главное меню", "home")));
        return Map.of("inline_keyboard", rows);
    }
    static String home(String name) {
        return "Привет, " + escape(name) + " 👋\n\n<b>VUniq · студия копий</b> 🎬\n"
                + "Создавай варианты фото и видео в несколько нажатий.\n\n"
                + "✨ <b>Basic</b> — мягкое изменение скорости и масштаба.\n"
                + "🔥 <b>Hard</b> — более заметные изменения и коррекция цвета.\n\n"
                + "🎁 <b>Тестовый доступ: бесплатно</b>\nОба режима и до 10 копий доступны без оплаты.\n\n"
                + "Отправь фото или видео сюда 👇\n"
                + "SHA-256 показывает различие байтов, а не гарантирует уникальность для соцсетей.";
    }
    static String settings(BotUserStore.Profile p) {
        return "⚙️ <b>Настройки обработки</b>\n\nРежим: <b>" + (p.hard() ? "🔥 Hard" : "✨ Basic")
                + "</b>\nКопий с каждого исходника: <b>" + p.copies() + "</b>\n\n"
                + "Выбери режим и количество кнопками ниже. Настройки сохранятся.\n"
                + "Hard заметнее меняет масштаб и цвет. Для видео также меняется скорость.\n\n"
                + "Все функции сейчас бесплатны.";
    }
    static String subscription(BotUserStore.Profile p) {
        return "💎 <b>Подписка и статистика</b>\n\n🧪 Тестовый доступ — <b>без оплаты</b>.\n"
                + "Платная подписка не оформлена. Ограничения по исходникам отключены.\n\n"
                + "📊 В этом месяце: <b>" + p.originals() + "</b> исходников, <b>" + p.results() + "</b> копий.\n"
                + "🎁 Демонстрационный счётчик: осталось " + Math.max(0, 3 - p.originals()) + " из 3 исходников.\n"
                + "Даже при нуле можно продолжать бесплатно.\n"
                + "🗓 Сброс статистики: " + p.month().plusMonths(1).atDay(1).format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
                + " (Москва).\n\n<b>Макет будущих тарифов</b>\n"
                + "💎 Basic · 990 ₽/мес — до 50 исходников, до 5 копий.\n"
                + "🚀 PRO · 4 990 ₽/мес — безлимит исходников, Basic + Hard, до 10 копий.\n\n"
                + "Это демонстрационные условия из референса. Оплата и приоритетная очередь не подключены.";
    }
    static Object subscriptionKeys() {
        return Map.of("inline_keyboard", List.of(List.of(button("💎 Basic · 990 ₽ — демо", "pay:basic")),
                List.of(button("🚀 PRO · 4 990 ₽ — демо", "pay:pro")), List.of(button("← Главное меню", "home"))));
    }
    static String help() {
        return "📖 <b>Как это работает</b>\n\n1. Выбери режим и 1–10 копий в /settings.\n"
                + "2. Отправь MP4/MOV или фото JPEG/PNG до 20 МБ.\n3. Следи за прогрессом и получай результаты.\n\n"
                + "Фото — до 20 мегапикселей; результат JPEG-файлом. Видео — MP4 с плеером. "
                + "Для точного исходного хеша отправляй медиа как файл.\n\n"
                + "Подпись необязательна: <code>speed=1.01 scale=0.98</code>. "
                + "Она задаёт параметры первой копии; следующие немного отличаются. Для фото speed не применяется.\n\n"
                + "Чёрные поля — результат уменьшения изображения. SHA-256 не гарантирует уникальность для платформ.\n"
                + "Оплата отключена. /id — ID чата.";
    }
    public static String escape(String text) { return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
}
