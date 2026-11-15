package ru.rainedev.raine.tools;

import java.util.List;

/**
 * Помощник для тестов: эмулирует логику {@link ru.rainedev.raine.telegram.Telegram#collect},
 * не требуя поднимать весь Telegram-клиент. Дублирует правила, чтобы тест
 * проверял именно их.
 */
public final class EmojiTestHelper {

    private EmojiTestHelper() {}

    public static void collect(List<String> into, String emoji) {
        if (!into.contains(emoji)) {
            into.add(emoji);
        }
    }

    public static void collectCustom(List<String> into, long customEmojiId) {
        String tag = "custom:" + customEmojiId;
        if (!into.contains(tag)) {
            into.add(tag);
        }
    }
}
