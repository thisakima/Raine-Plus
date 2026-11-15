package ru.rainedev.raine.tools;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Инструменты управления собственным профилем: имя, био, аватарка.
 * Она сама решает, что менять — как человек в настройках Telegram.
 */
class ProfileToolsTest {

    @Test
    void profileToolsExist() throws Exception {
        String content = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("src/main/java/ru/rainedev/raine/tools/TelegramTools.java")), "UTF-8");
        assertTrue(content.contains("\"my_profile\""), "должен быть my_profile");
        assertTrue(content.contains("\"set_my_bio\""), "должен быть set_my_bio");
        assertTrue(content.contains("\"set_my_name\""), "должен быть set_my_name");
        assertTrue(content.contains("\"set_my_photo\""), "должен быть set_my_photo");
        assertTrue(content.contains("\"clear_my_photo\""), "должен быть clear_my_photo");
    }

    @Test
    void bioToolMentionsLimit() throws Exception {
        String content = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("src/main/java/ru/rainedev/raine/tools/TelegramTools.java")), "UTF-8");
        assertTrue(content.contains("70"), "био должен упоминать лимит 70 символов");
    }

    @Test
    void photoToolRequiresGalleryFile() throws Exception {
        String content = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("src/main/java/ru/rainedev/raine/tools/TelegramTools.java")), "UTF-8");
        assertTrue(content.contains("resolveMedia"), "аватарка должна браться из галереи");
        assertTrue(content.contains("isPlainFilename"), "имя файла должно проверяться на безопасность");
    }

    @Test
    void systemPromptDescribesProfileManagement() throws Exception {
        String content = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("prompts/system.md")), "UTF-8");
        assertTrue(content.contains("my_profile") || content.contains("#my_profile"),
                "system.md должен упоминать my_profile");
        assertTrue(content.contains("set_my_bio") || content.contains("#set_my_bio"),
                "system.md должен упоминать set_my_bio");
        assertTrue(content.contains("set_my_name") || content.contains("#set_my_name"),
                "system.md должен упоминать set_my_name");
        assertTrue(content.contains("set_my_photo") || content.contains("#set_my_photo"),
                "system.md должен упоминать set_my_photo");
        assertTrue(content.contains("clear_my_photo") || content.contains("#clear_my_photo"),
                "system.md должен упоминать clear_my_photo");
    }

    @Test
    void systemPromptListsHardLimits() throws Exception {
        String content = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("prompts/system.md")), "UTF-8");
        assertTrue(content.contains("phone number"), "должен запрещать смену номера");
        assertTrue(content.contains("password") || content.contains("2FA"),
                "должен запрещать смену пароля");
        assertTrue(content.contains("Delete the account") || content.contains("delete the account"),
                "должен запрещать удаление аккаунта");
        assertTrue(content.contains("Impersonate") || content.contains("impersonate"),
                "должен запрещать имперсонацию");
    }

    @Test
    void toolsAreInAlwaysSet() throws Exception {
        String content = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("src/main/java/ru/rainedev/raine/RaineApp.java")), "UTF-8");
        assertTrue(content.contains("tools.myProfile()"), "myProfile должен быть в always");
        assertTrue(content.contains("tools.setMyBio()"), "setMyBio должен быть в always");
        assertTrue(content.contains("tools.setMyName()"), "setMyName должен быть в always");
        assertTrue(content.contains("tools.setMyPhoto()"), "setMyPhoto должен быть в always");
        assertTrue(content.contains("tools.clearMyPhoto()"), "clearMyPhoto должен быть в always");
    }
}
