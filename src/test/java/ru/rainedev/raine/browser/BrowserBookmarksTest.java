package ru.rainedev.raine.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.rainedev.raine.config.Config;
import ru.rainedev.raine.core.Tool;

/**
 * Её закладки: список сайтов она ведёт сама, без браузера — значит проверяется
 * без запуска Playwright.
 */
class BrowserBookmarksTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static BrowserTools tools(Path dir) {
        Config.Browser config = new Config.Browser(true, true, "firefox", "firefox", dir, "ru-RU",
                "Europe/Moscow", 1280, 800, "", "");
        return new BrowserTools(new Browser(config), new HumanInput(new Random(1)), null,
                dir.resolve("bookmarks.md"), dir.resolve("gallery"));
    }

    private static String call(Tool tool, String json) throws Exception {
        return tool.call(MAPPER.readTree(json));
    }

    private static Tool byName(BrowserTools tools, String name) {
        return tools.tools().stream().filter(tool -> tool.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void atFirstSheHasNoSitesOfHerOwn(@TempDir Path dir) throws Exception {
        String list = call(byName(tools(dir), "browser_bookmarks"), "{}");
        assertTrue(list.contains("haven't kept any sites"), "список пуст — так и сказано: " + list);
    }

    @Test
    void sheKeepsASiteWithHerOwnNote(@TempDir Path dir) throws Exception {
        BrowserTools tools = tools(dir);
        String saved = call(byName(tools, "browser_bookmark_save"),
                "{\"url\":\"https://pinterest.com\",\"note\":\"доски с готикой, залипаю перед сном\"}");
        assertTrue(saved.contains("Kept https://pinterest.com"), saved);

        String list = call(byName(tools, "browser_bookmarks"), "{}");
        assertTrue(list.contains("https://pinterest.com"), list);
        assertTrue(list.contains("доски с готикой"), "её пометка важнее адреса: " + list);
        assertTrue(Files.exists(dir.resolve("bookmarks.md")), "закладки живут на диске");
    }

    @Test
    void theSameSiteIsNotKeptTwice(@TempDir Path dir) throws Exception {
        BrowserTools tools = tools(dir);
        Tool save = byName(tools, "browser_bookmark_save");
        call(save, "{\"url\":\"https://example.com\",\"note\":\"первая пометка\"}");
        call(save, "{\"url\":\"https://example.com\",\"note\":\"вторая пометка\"}");

        String list = call(byName(tools, "browser_bookmarks"), "{}");
        assertEquals(1, count(list, "https://example.com"), "адрес один, иначе список зарастёт: " + list);
        assertTrue(list.contains("вторая пометка"), "пометка свежая: " + list);
        assertFalse(list.contains("первая пометка"), list);
    }

    @Test
    void addressWithoutSchemeIsCompleted(@TempDir Path dir) throws Exception {
        BrowserTools tools = tools(dir);
        call(byName(tools, "browser_bookmark_save"), "{\"url\":\"yandex.ru/pogoda\"}");
        String list = call(byName(tools, "browser_bookmarks"), "{}");
        assertTrue(list.contains("https://yandex.ru/pogoda"), list);
    }

    @Test
    void withoutAddressNothingIsKept(@TempDir Path dir) throws Exception {
        BrowserTools tools = tools(dir);
        String answer = call(byName(tools, "browser_bookmark_save"), "{\"note\":\"без адреса\"}");
        assertTrue(answer.contains("didn't say which address"), answer);
        assertFalse(Files.exists(dir.resolve("bookmarks.md")));
    }

    /** Дверь в браузер есть в наборе и не диктует, куда идти. */
    @Test
    void theBrowserIsHersToDirect(@TempDir Path dir) {
        Tool start = tools(dir).start(List.of());
        assertEquals("open_browser", start.name());
        String description = start.description().toLowerCase();
        assertTrue(description.contains("you choose where to go"), start.description());
        assertFalse(description.contains("first open"), "никакого порядка действий: " + start.description());
    }

    /** В наборе — и обычные инструменты, и закладки: всё, чтобы ходить самой. */
    @Test
    void sheHasHandsForAnySite(@TempDir Path dir) {
        List<String> names = tools(dir).tools().stream().map(Tool::name).toList();
        assertTrue(names.containsAll(List.of("browser_open", "browser_snapshot", "browser_click",
                "browser_type", "browser_scroll", "browser_press", "browser_back", "browser_forward",
                "browser_reload", "browser_hover", "browser_select", "browser_upload", "browser_download",
                "browser_tabs", "browser_new_tab", "browser_switch_tab", "browser_close_tab",
                "browser_web_search", "browser_screenshot", "browser_bookmarks", "browser_bookmark_save")),
                names.toString());
    }

    /** Поиск в настоящем браузере — основной способ, а не запасной. */
    @Test
    void sheLooksThingsUpHerselfInTheBrowser(@TempDir Path dir) {
        Tool search = tools(dir).tools().stream()
                .filter(tool -> tool.name().equals("browser_web_search")).findFirst().orElseThrow();
        assertTrue(search.description().contains("real browser"), search.description());
        assertTrue(search.description().contains("click through"), search.description());
    }

    /** Снимок экрана сохраняется туда, откуда его можно отправить в Telegram. */
    @Test
    void screenshotLandsWhereSendingLooksForIt(@TempDir Path dir) {
        Tool shot = tools(dir).tools().stream()
                .filter(tool -> tool.name().equals("browser_screenshot")).findFirst().orElseThrow();
        assertTrue(shot.description().contains("photo_filename"), shot.description());
        assertTrue(shot.description().contains("post_story"), shot.description());
    }

    private static int count(String text, String piece) {
        int total = 0;
        int at = 0;
        while ((at = text.indexOf(piece, at)) >= 0) {
            total++;
            at += piece.length();
        }
        return total;
    }
}
