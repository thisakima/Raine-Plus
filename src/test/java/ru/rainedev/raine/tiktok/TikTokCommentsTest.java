package ru.rainedev.raine.tiktok;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.rainedev.raine.browser.Browser;
import ru.rainedev.raine.browser.HumanInput;
import ru.rainedev.raine.config.Config;
import ru.rainedev.raine.core.Tool;

/**
 * Комментарии: как они показываются ей и что она может с ними сделать.
 * Без браузера — проверяется разметка строк и состав инструментов.
 */
class TikTokCommentsTest {

    private static TikTokTools tools(Path dir) {
        Config.TikTok config = new Config.TikTok(true, "yukiko", dir.resolve("tiktok"), 12,
                0.35, 0.06, 0.04, 60, 8, 5, 10, 90, 3);
        Browser browser = new Browser(new Config.Browser(true, true, "firefox", dir, "ru-RU",
                "Europe/Moscow", 1280, 800, "", ""));
        return new TikTokTools(browser, new HumanInput(new Random(1)), null, config,
                new TikTokLimits(config, dir.resolve("limits.properties")), new Random(1));
    }

    private static Tool byName(Path dir, String name) {
        return tools(dir).tools().stream()
                .filter(tool -> tool.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void sheSeesWhoSaidWhatAndHowManyAgree() {
        String line = new TikTok.Comment(1, "hater", "это болезнь, надо лечить", "14", "2 д назад", 1)
                .render();
        assertTrue(line.startsWith("[1] @hater: это болезнь, надо лечить"), line);
        assertTrue(line.contains("14 likes"), "число лайков показывает, кого поддержали: " + line);
        assertTrue(line.contains("2 д назад"), line);
    }

    @Test
    void replyInsideAThreadIsVisiblyNested() {
        String line = new TikTok.Comment(3, "deep", "ответ в ветке", "3", "", 2).render();
        assertTrue(line.contains("↳"), "ответ на комментарий должен отличаться от верхнего: " + line);
    }

    @Test
    void emptyMetaDoesNotLeaveHollowBrackets() {
        String line = new TikTok.Comment(2, "ok", "нормально всё", "", "", 1).render();
        assertEquals("[2] @ok: нормально всё", line.strip());
    }

    @Test
    void sheCanReadAnswerAndLeaveComments(@TempDir Path dir) {
        List<String> names = tools(dir).tools().stream()
                .map(Tool::name).toList();
        assertTrue(names.containsAll(List.of("tiktok_comments", "tiktok_reply",
                "tiktok_comments_close")), names.toString());
    }

    @Test
    void readingMoreCommentsIsHerChoice(@TempDir Path dir) {
        Tool comments = byName(dir, "tiktok_comments");
        assertTrue(comments.parameters().path("properties").has("more"),
                "должна уметь дочитать дальше, а не только верх");
        assertFalse(comments.parameters().path("required").toString().contains("more"),
                "но не обязана: читать дальше или нет — её решение");
    }

    @Test
    void answeringNeedsTheCommentAndHerWords(@TempDir Path dir) {
        Tool reply = byName(dir, "tiktok_reply");
        String required = reply.parameters().path("required").toString();
        assertTrue(required.contains("comment"), "нужен номер комментария: " + required);
        assertTrue(required.contains("text"), "нужно, что она хочет сказать: " + required);
    }

    /** Ответ — её решение, а не обязанность: это и есть живость. */
    @Test
    void replyingIsOptionalAndInHerOwnVoice(@TempDir Path dir) {
        String description = byName(dir, "tiktok_reply").description().toLowerCase();
        assertTrue(description.contains("don't have to answer"), description);
        assertTrue(description.contains("your own voice") || description.contains("in your own"),
                description);
        assertFalse(description.contains("always reply"), "никакого «отвечай всем»: " + description);
    }

    /** Чтение комментариев не должно требовать зрения: текст и так перед глазами. */
    @Test
    void commentsToolDoesNotPromiseAScreenshot(@TempDir Path dir) {
        String description = byName(dir, "tiktok_comments").description().toLowerCase();
        assertTrue(description.contains("read"), description);
        assertFalse(description.contains("screenshot"), description);
    }
}
