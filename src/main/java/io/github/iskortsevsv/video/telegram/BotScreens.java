package io.github.iskortsevsv.video.telegram;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Формирует тексты экранов и клавиатуры Telegram-бота. */
public final class BotScreens {
    private BotScreens() {}

    static Map<String, String> button(String text, String data) {
        return Map.of("text", text, "callback_data", data);
    }

    /** Создаёт кнопки главного меню. */
    public static Object homeKeys() {
        return Map.of(
                "inline_keyboard",
                List.of(
                        List.of(button("🎬 Обработать файл", "upload")),
                        List.of(
                                button("⚙️ Настройки", "settings"),
                                button("💎 Подписка", "subscription")),
                        List.of(button("📖 Как это работает", "help"))));
    }

    static Object backKeys() {
        return Map.of("inline_keyboard", List.of(List.of(button("← Главное меню", "home"))));
    }

    static Object settingsKeys(BotUserStore.Profile p, CopyAccess access) {
        var rows = new ArrayList<List<Map<String, String>>>();
        rows.add(
                List.of(
                        button((p.hard() ? "" : "✓ ") + "✨ Basic", "mode:basic"),
                        button((p.hard() ? "✓ " : "") + "🔥 Hard", "mode:hard")));
        addCopyButtons(rows, "Видео", "video:", access.selected(p, false), access.videos);
        addCopyButtons(rows, "Фото", "photo:", access.selected(p, true), access.photos);
        rows.add(List.of(button("← Главное меню", "home")));
        return Map.of("inline_keyboard", rows);
    }

    private static void addCopyButtons(List<List<Map<String, String>>> rows, String media,
            String prefix, int selected, int maximum) {
        for (int first = 1; first <= maximum; first += 5) {
            var row = new ArrayList<Map<String, String>>();
            for (int i = first; i < first + 5 && i <= maximum; i++) {
                row.add(button((selected == i ? "✓ " : "") + media + " " + i, prefix + i));
            }
            rows.add(row);
        }
    }

    static String home(String name) {
        return "Привет, "
                + escape(name)
                + " 👋\n\n<b>VUniq · студия копий</b> 🎬\n"
                + "Создавай варианты фото и видео в несколько нажатий.\n\n"
                + "✨ <b>Basic</b> — мягкое изменение скорости и масштаба.\n"
                + "🔥 <b>Hard</b> — более заметные изменения и коррекция цвета.\n\n"
                + "🎁 <b>Тестовый доступ: бесплатно</b>\nВыбери число копий отдельно для фото и видео в /settings.\n\n"
                + "Отправь фото или видео сюда 👇\n"
                + "SHA-256 показывает различие байтов, а не гарантирует уникальность для соцсетей.";
    }

    static String settings(BotUserStore.Profile p, CopyAccess access) {
        return "⚙️ <b>Настройки обработки</b>\n\nРежим: <b>"
                + (p.hard() ? "🔥 Hard" : "✨ Basic")
                + "</b>\nДоступ: <b>" + access.label + "</b>\n"
                + "Видео: " + access.selected(p, false) + " из " + access.videos
                + " · Фото: " + access.selected(p, true) + " из " + access.photos + "\n\n"
                + "Выбери режим и количество кнопками ниже. Настройки сохранятся.\n"
                + "Hard для видео приближает центр кадра и заметнее меняет цвет.\n\n"
                + "Все функции сейчас бесплатны.";
    }

    static String subscription(BotUserStore.Profile p, CopyAccess access) {
        return "💎 <b>Подписка и статистика</b>\n\n🧪 Тестовый доступ — <b>без оплаты</b>.\n"
                + "Платная подписка не оформлена. Ограничения по исходникам отключены.\n\n"
                + "📊 В этом месяце: <b>"
                + p.originals()
                + "</b> исходников, <b>"
                + p.results()
                + "</b> копий.\n"
                + "🎁 Демонстрационный счётчик: осталось "
                + Math.max(0, 3 - p.originals())
                + " из 3 исходников.\n"
                + "Даже при нуле можно продолжать бесплатно.\n"
                + "🗓 Сброс статистики: "
                + p.month().plusMonths(1).atDay(1).format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
                + " (Москва).\n\nДоступ: " + access.label + "\n"
                + "Лимит для видео: " + access.videos + ".\n"
                + "Лимит для фото: " + access.photos + ".\n\n"
                + "Кнопки оплаты демонстрационные.";
    }

    static Object subscriptionKeys() {
        return Map.of(
                "inline_keyboard",
                List.of(
                        List.of(button("💎 Basic · 990 ₽ — демо", "pay:basic")),
                        List.of(button("🚀 High · 4 990 ₽ — демо", "pay:pro")),
                        List.of(button("← Главное меню", "home"))));
    }

    static String help(int maxInputMb) {
        return "📖 <b>Как это работает</b>\n\n1. Выбери режим и число копий для фото и видео в /settings.\n"
                + "2. Отправь MP4/MOV или фото JPEG/PNG до " + maxInputMb + " МБ.\n"
                + "3. Следи за прогрессом и получай результаты.\n\n"
                + "Фото — до 20 мегапикселей; результат JPEG-файлом. Видео — MP4 с плеером. "
                + "Для точного исходного хеша отправляй медиа как файл.\n\n"
                + "Подпись необязательна: <code>speed=1.01 scale=0.98</code>. "
                + "Она задаёт параметры первой копии; следующие немного отличаются. Для фото speed не применяется.\n\n"
                + "Чёрные поля — результат уменьшения изображения. SHA-256 не гарантирует уникальность для платформ.\n"
                + "Оплата отключена. /id — ID чата.";
    }

    /** Экранирует специальные символы текста для Telegram HTML. */
    public static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
