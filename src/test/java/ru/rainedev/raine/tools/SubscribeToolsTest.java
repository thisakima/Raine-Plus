package ru.rainedev.raine.tools;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Инструменты подписки: она сама решает, на кого подписаться,
 * как человек — увидела интересного автора и пошла искать.
 */
class SubscribeToolsTest {

    @Test
    void findChatExistsInTools() throws Exception {
        // проверяем, что инструмент существует и правильно описан
        String content = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("src/main/java/ru/rainedev/raine/tools/TelegramTools.java")), "UTF-8");
        assertTrue(content.contains("find_chat"), "инструмент find_chat должен существовать");
        assertTrue(content.contains("@username"), "должен принимать юзернейм");
    }

    @Test
    void joinChatExistsInTools() throws Exception {
        String content = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("src/main/java/ru/rainedev/raine/tools/TelegramTools.java")), "UTF-8");
        assertTrue(content.contains("join_chat"), "инструмент join_chat должен существовать");
        assertTrue(content.contains("chat_id"), "должен принимать chat_id");
        assertTrue(content.contains("link"), "должен принимать ссылку из bio");
        assertTrue(content.contains("your call") || content.contains("It's your call"),
                "должно быть сказано, что это её решение");
    }

    @Test
    void promptsMentionSubscription() throws Exception {
        String charBase = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("prompts/character_base.md")), "UTF-8");
        assertTrue(charBase.contains("#find_chat"), "character_base должен упоминать find_chat");
        assertTrue(charBase.contains("#join_chat"), "character_base должен упоминать join_chat");
        assertTrue(charBase.contains("my own call") || charBase.contains("My own call"),
                "должно быть сказано, что это её решение");

        String system = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("prompts/system.md")), "UTF-8");
        assertTrue(system.contains("find_chat"), "system.md должен упоминать find_chat");
        assertTrue(system.contains("join_chat"), "system.md должен упоминать join_chat");
    }
}
