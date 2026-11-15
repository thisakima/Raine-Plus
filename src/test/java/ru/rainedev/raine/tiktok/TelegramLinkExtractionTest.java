package ru.rainedev.raine.tiktok;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Извлечение Telegram-ссылок из bio и подписей: без этого она угадывала бы
 * телеграм-ник по тикток-нику, а они почти всегда разные.
 */
class TelegramLinkExtractionTest {

    @Test
    void extractsPlainTmeLink() {
        List<TikTok.TelegramLink> links = TikTok.telegramLinks("тг: t.me/mychannel");
        assertEquals(1, links.size());
        assertEquals("mychannel", links.get(0).handle());
        assertFalse(links.get(0).isInvite());
        assertEquals("https://t.me/mychannel", links.get(0).url());
    }

    @Test
    void extractsFullUrl() {
        List<TikTok.TelegramLink> links = TikTok.telegramLinks("заходи в мой телеграм: https://t.me/coolchannel");
        assertEquals(1, links.size());
        assertEquals("coolchannel", links.get(0).handle());
    }

    @Test
    void extractsInviteLink() {
        List<TikTok.TelegramLink> links = TikTok.telegramLinks("вступай: t.me/+AbCdEf123");
        assertEquals(1, links.size());
        assertTrue(links.get(0).isInvite());
        assertEquals("+AbCdEf123", links.get(0).invite());
        assertEquals("https://t.me/+AbCdEf123", links.get(0).url());
    }

    @Test
    void extractsJoinchatInvite() {
        List<TikTok.TelegramLink> links = TikTok.telegramLinks("ссылка: telegram.me/joinchat/XYZ123");
        assertEquals(1, links.size());
        assertTrue(links.get(0).isInvite());
    }

    @Test
    void atMentionOnlyWithTelegramContext() {
        // @ник без слова «тг/телеграм» может быть упоминанием другого тиктокера,
        // а не телеграм-каналом — не извлекаем, чтобы не угадывать зря.
        assertTrue(TikTok.telegramLinks("смотрите @friend123").isEmpty());
        // со словом-признаком — извлекаем:
        List<TikTok.TelegramLink> withContext = TikTok.telegramLinks("мой тг @friend123");
        assertEquals(1, withContext.size());
        assertEquals("friend123", withContext.get(0).handle());
    }

    @Test
    void skipsReservedTmePaths() {
        assertTrue(TikTok.telegramLinks("стикеры: t.me/addstickers").isEmpty());
        assertTrue(TikTok.telegramLinks("эмо: t.me/addemoji").isEmpty());
    }

    @Test
    void deduplicatesLinks() {
        List<TikTok.TelegramLink> links = TikTok.telegramLinks(
                "тг: t.me/one и ещё раз t.me/one");
        assertEquals(1, links.size());
    }

    @Test
    void emptyTextGivesNoLinks() {
        assertTrue(TikTok.telegramLinks(null).isEmpty());
        assertTrue(TikTok.telegramLinks("").isEmpty());
        assertTrue(TikTok.telegramLinks("просто текст без ссылок").isEmpty());
    }

    @Test
    void videoInfoDescribeShowsTelegramLinkFromCaption() {
        var info = new TikTok.VideoInfo(
                "https://www.tiktok.com/@author/video/123", "author",
                "новый ролик! тг: t.me/authorchannel", "", List.of());
        String described = info.describe();
        assertTrue(described.contains("telegram mentioned here"), described);
        assertTrue(described.contains("authorchannel"), described);
    }

    @Test
    void profileInfoDescribeShowsBioAndTelegram() {
        var info = new TikTok.ProfileInfo(
                "tiktokuser", "Имя", "тут мой тг: t.me/realname", "12.3K", "456K",
                TikTok.telegramLinks("тут мой тг: t.me/realname"));
        String described = info.describe();
        assertTrue(described.contains("bio: тут мой тг: t.me/realname"), described);
        assertTrue(described.contains("realname"), described);
        assertTrue(described.contains("followers: 12.3K"), described);
    }

    @Test
    void profileInfoWithoutTelegramSaysSo() {
        var info = new TikTok.ProfileInfo(
                "tiktokuser", "Имя", "просто описание", "100", "200", List.of());
        String described = info.describe();
        assertTrue(described.contains("none found in the bio"), described);
        assertTrue(described.contains("don't guess the nickname"), described);
    }
}
