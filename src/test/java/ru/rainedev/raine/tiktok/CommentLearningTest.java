package ru.rainedev.raine.tiktok;

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
import ru.rainedev.raine.core.PhraseCollector;

/**
 * Чему она учится в комментариях: живую манеру забирает, нейтральное и своё
 * собственное — нет.
 */
class CommentLearningTest {

    private static final String ME = "yukiko";

    private static TikTokTools tools(Path dir, PhraseCollector collector) {
        Config.TikTok config = new Config.TikTok(true, ME, dir.resolve("tiktok"), 12,
                0.35, 0.06, 0.04, 60, 8, 5, 10, 90, 3);
        Browser browser = new Browser(new Config.Browser(true, true, "firefox", "firefox", dir, "ru-RU",
                "Europe/Moscow", 1280, 800, "", ""));
        TikTokTools tools = new TikTokTools(browser, new HumanInput(new Random(1)), null, config,
                new TikTokLimits(config, dir.resolve("limits.properties")), new Random(1));
        tools.phrases(collector);
        return tools;
    }

    private static TikTok.Comment comment(int ref, String author, String text) {
        return new TikTok.Comment(ref, author, text, "3", "", 1);
    }

    @Test
    void shePicksUpHowTeenagersActuallyWrite(@TempDir Path dir) {
        PhraseCollector collector = new PhraseCollector(dir.resolve("phrases.txt"), 1L);
        tools(dir, collector).learnFrom(List.of(
                comment(1, "someone", "ахахах база, жиза"),
                comment(2, "other", "кринж какой, ору")));

        String sample = collector.sample();
        assertTrue(sample.contains("база, жиза"), "живое должно оседать: " + sample);
        assertTrue(sample.contains("ору"), sample);
    }

    @Test
    void sheDoesNotLearnFromHerself(@TempDir Path dir) {
        PhraseCollector collector = new PhraseCollector(dir.resolve("phrases.txt"), 1L);
        tools(dir, collector).learnFrom(List.of(
                comment(1, ME, "ахахах ну вы даёте"),
                comment(2, "@" + ME.toUpperCase(), "ОРУ с этого")));

        String sample = collector.sample();
        assertFalse(sample.contains("ну вы даёте"), "свои реплики учить нельзя: " + sample);
        assertFalse(sample.contains("ОРУ с этого"), "и с другим регистром ника тоже: " + sample);
    }

    @Test
    void neutralCommentsAreNotWorthLearning(@TempDir Path dir) {
        PhraseCollector collector = new PhraseCollector(dir.resolve("phrases.txt"), 1L);
        tools(dir, collector).learnFrom(List.of(
                comment(1, "someone", "Сегодня ожидается небольшой дождь в Москве")));

        assertTrue(collector.sample().isEmpty(), "нейтральное учится нечему");
    }

    @Test
    void withoutACollectorReadingStillWorks(@TempDir Path dir) {
        Config.TikTok config = new Config.TikTok(true, ME, dir.resolve("tiktok"), 12,
                0.35, 0.06, 0.04, 60, 8, 5, 10, 90, 3);
        Browser browser = new Browser(new Config.Browser(true, true, "firefox", "firefox", dir, "ru-RU",
                "Europe/Moscow", 1280, 800, "", ""));
        TikTokTools tools = new TikTokTools(browser, new HumanInput(new Random(1)), null, config,
                new TikTokLimits(config, dir.resolve("limits.properties")), new Random(1));
        // коллекцию не подключали — чтение комментариев не должно из-за этого падать
        tools.learnFrom(List.of(comment(1, "someone", "ахахах жиза")));
    }

    @Test
    void commentsAreLearnedFromAnySourceNotOnlyChats(@TempDir Path dir) {
        PhraseCollector collector = new PhraseCollector(dir.resolve("phrases.txt"), 1L);
        collector.observeText("рофл, а я уж поверила");
        collector.observeText("ну всё, разошлись");
        String sample = collector.sample();
        assertTrue(sample.contains("рофл"), sample);
        assertTrue(sample.contains("разошлись"), sample);
    }
}
