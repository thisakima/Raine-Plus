package ru.rainedev.raine.browser;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.BoundingBox;
import com.microsoft.playwright.options.LoadState;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.core.Tool;
import ru.rainedev.raine.vision.Vision;

/**
 * Инструменты браузера: открыть, смотреть, кликать, печатать, листать.
 * <p>
 * Каждое действие идёт через {@link HumanInput} (по-человечески: с промахом
 * курсора, паузами, живой скоростью печати) и возвращается свежим снимком
 * страницы — так она сразу видит, к чему привёл клик. Снимок разбирает зрение,
 * поэтому она понимает и картинку, а не только список кнопок.
 * <p>
 * Это тело общее для любого сайта: TikTok — лишь первая специализация поверх
 * него (см. пакет TikTok-инструментов), а вход на любой сайт живёт в одном
 * постоянном профиле.
 */
public final class BrowserTools {

    private static final Logger log = LoggerFactory.getLogger(BrowserTools.class);

    /** Поисковик по умолчанию — без страниц согласия, удобнее для автомата. */
    private static final String SEARCH_BASE = "https://duckduckgo.com/?q=";

    /** Сколько пикселей листать, если она не сказала. */
    private static final int DEFAULT_SCROLL = 600;

    /** Столько её сайтов показываем: больше — и список начнет вытеснять саму страницу. */
    private static final int MAX_BOOKMARKS = 30;

    private final Browser browser;
    private final HumanInput input;
    private final Vision vision;

    /** Её собственные закладки: адрес и её пометка, зачем он ей. */
    private final Path bookmarks;

    /**
     * Куда складывать снимки экрана. Та же галерея, где лежат её фото: отправку
     * в Telegram ищет файл по имени именно там, так что скриншот можно сразу
     * послать в чат или выложить историей.
     */
    private final Path gallery;

    /** Результат одного действия: короткая заметка и то, что теперь на экране. */
    private record Outcome(String note, BrowserSnapshot snapshot) {}

    public BrowserTools(Browser browser, HumanInput input, Vision vision, Path bookmarks, Path gallery) {
        this.browser = browser;
        this.input = input;
        this.vision = vision;
        this.bookmarks = bookmarks;
        this.gallery = gallery;
    }

    /** Все инструменты браузера — их добавляют в набор для порывов. */
    public List<Tool> tools() {
        return List.of(open(), snapshot(), click(), type(), scroll(), press(), back(), forward(),
                reload(), hover(), selectOption(), upload(), download(), tabs(), newTab(),
                switchTab(), closeTab(), closeBrowser(), webSearch(), screenshot(), bookmarks(), saveBookmark());
    }

    /**
     * Вход в браузер из любого хода — чата, порыва, уведомления.
     * <p>
     * В постоянном наборе живёт только этот один инструмент: восемь инструментов
     * браузера на каждом ходу раздували бы контекст впустую, а нужны они лишь
     * тогда, когда она сама решила пойти в сеть. Поэтому полный набор выдаётся
     * по её собственному желанию, уже внутри хода, и исчезает вместе с ним.
     * <p>
     * Никакого порядка действий здесь нет: куда идти, что нажимать и что делать
     * с увиденным — решает она.
     *
     * @param alongside инструменты, которые стоит выдать вместе с браузерными
     *                  (например, TikTok), если они включены
     */
    public Tool start(List<Tool> alongside) {
        return Tool.named("open_browser")
                .describedAs("Open your browser — your own window with your logins kept, separate from "
                        + "anyone else's. Nothing is scripted: you choose where to go, what to click and "
                        + "what to do with what you find. Gives you the browser controls for this turn: "
                        + "open pages, look at them, click, type, scroll, keys, back/forward/reload, hover "
                        + "over things, pick dropdown options, upload files, download files, open new tabs "
                        + "and switch between them, take screenshots, search the web, and keep bookmarks.")
                .buildContextual((arguments, addTool) -> {
                    for (Tool tool : tools()) {
                        addTool.accept(tool);
                    }
                    if (alongside != null) {
                        alongside.forEach(addTool);
                    }
                    String where;
                    try {
                        where = browser.onPage(page -> {
                            String url = page.url();
                            return url == null || url.isBlank() || url.startsWith("about:")
                                    ? "a blank page"
                                    : url;
                        });
                    } catch (RuntimeException e) {
                        return "The browser didn't open: " + rootMessage(e);
                    }
                    List<String> saved = bookmarkList();
                    StringBuilder out = new StringBuilder("Your browser is open on ").append(where).append(".\n");
                    out.append("Tabs:\n").append(browser.describeTabs()).append('\n');
                    if (saved.isEmpty()) {
                        out.append("No bookmarks yet. Go anywhere you feel like — #browser_open with any ")
                                .append("address, or #browser_web_search to look something up. When a place ")
                                .append("starts feeling like yours, keep it with #browser_bookmark_save so ")
                                .append("you can come back on your own.");
                    } else {
                        out.append("Your bookmarks — places you kept for yourself:\n")
                                .append(String.join("\n", saved)).append('\n')
                                .append("Go where you feel like: one of these, #browser_open with any ")
                                .append("address, or #browser_web_search.");
                    }
                    return out.toString();
                });
    }

    public Tool open() {
        return Tool.named("browser_open")
                .describedAs("Open a web page in your browser by URL. Returns what you see: interactive "
                        + "elements with ref numbers, the page text, and a description of the screen.")
                .requiredString("url", "Full URL, e.g. https://example.com")
                .build(args -> {
                    String url = args.path("url").asText("");
                    if (url.isBlank()) {
                        return "You didn't say which page to open.";
                    }
                    String target = normalizeUrl(url);
                    return run(page -> {
                        page.navigate(target);
                        settle(page);
                        return "Opened " + target;
                    });
                });
    }

    public Tool snapshot() {
        return Tool.named("browser_snapshot")
                .describedAs("Look at the current page again: interactive elements with ref numbers, the "
                        + "page text, and a description of the screen. Use it after the page changes.")
                .build(args -> run(null));
    }

    public Tool click() {
        return Tool.named("browser_click")
                .describedAs("Click an element from the last snapshot by its ref number, like a person "
                        + "would (the cursor moves and there's a small pause).")
                .requiredInteger("ref", "The ref number from the snapshot, e.g. 7")
                .build(args -> {
                    int ref = args.path("ref").asInt(-1);
                    if (ref < 0) {
                        return "You didn't say what to click (need a ref number).";
                    }
                    return run(page -> {
                        Locator target = page.locator(BrowserSnapshot.refSelector(ref)).first();
                        if (target.count() == 0) {
                            return "There's no element with ref " + ref
                                    + " now. Take a fresh browser_snapshot.";
                        }
                        input.click(page, target);
                        settle(page);
                        return "Clicked element " + ref + ".";
                    });
                });
    }

    public Tool type() {
        return Tool.named("browser_type")
                .describedAs("Type text into a field from the last snapshot (a search box, a comment "
                        + "field, a login form). Set submit=true to press Enter afterwards.")
                .requiredInteger("ref", "The ref of the field to type into")
                .requiredString("text", "What to type")
                .optionalBoolean("submit", "Press Enter after typing (default false)")
                .build(args -> {
                    int ref = args.path("ref").asInt(-1);
                    String text = args.path("text").asText("");
                    boolean submit = args.path("submit").asBoolean(false);
                    if (ref < 0) {
                        return "You didn't say where to type (need a ref number).";
                    }
                    return run(page -> {
                        Locator field = page.locator(BrowserSnapshot.refSelector(ref)).first();
                        if (field.count() == 0) {
                            return "There's no field with ref " + ref
                                    + " now. Take a fresh browser_snapshot.";
                        }
                        input.typeInto(page, field, text);
                        if (submit) {
                            input.press(page, "Enter");
                            settle(page);
                        }
                        return "Typed into field " + ref + (submit ? " and pressed Enter." : ".");
                    });
                });
    }

    public Tool scroll() {
        return Tool.named("browser_scroll")
                .describedAs("Scroll the page, e.g. to the next item in a feed. Direction is 'down' or 'up'.")
                .requiredString("direction", "'down' or 'up'")
                .optionalInteger("amount", "Roughly how many pixels to scroll (default ~600)")
                .build(args -> {
                    String direction = args.path("direction").asText("down").toLowerCase();
                    int requested = args.path("amount").asInt(0);
                    final int amount = requested > 0 ? requested : DEFAULT_SCROLL;
                    final int delta = direction.startsWith("up") ? -amount : amount;
                    return run(page -> {
                        input.scroll(page, delta);
                        settle(page);
                        return "Scrolled " + (delta < 0 ? "up" : "down") + " about " + amount + "px.";
                    });
                });
    }

    public Tool press() {
        return Tool.named("browser_press")
                .describedAs("Press a keyboard key on the page: Enter, Escape, ArrowDown, ArrowUp, Tab, etc.")
                .requiredString("key", "Key name, e.g. Enter, Escape, ArrowDown")
                .build(args -> {
                    String key = args.path("key").asText("");
                    if (key.isBlank()) {
                        return "You didn't say which key to press.";
                    }
                    return run(page -> {
                        input.press(page, key);
                        settle(page);
                        return "Pressed " + key + ".";
                    });
                });
    }

    public Tool back() {
        return Tool.named("browser_back")
                .describedAs("Go back to the previous page in the browser.")
                .build(args -> run(page -> {
                    page.goBack();
                    settle(page);
                    return "Went back a page.";
                }));
    }

    public Tool forward() {
        return Tool.named("browser_forward")
                .describedAs("Go forward to the page you came back from, like the forward arrow in a browser.")
                .build(args -> run(page -> {
                    input.think(100, 350);
                    page.goForward();
                    settle(page);
                    return "Went forward a page.";
                }));
    }

    public Tool reload() {
        return Tool.named("browser_reload")
                .describedAs("Reload the current page — like pressing F5. Useful when something didn't load "
                        + "or a page is showing stale content.")
                .build(args -> run(page -> {
                    input.think(100, 400);
                    page.reload();
                    settle(page);
                    return "Reloaded the page.";
                }));
    }

    /**
     * Наведение мышью: у сайтов половина всего спрятана в меню, которое
     * раскрывается только при наведении, — как у человека.
     */
    public Tool hover() {
        return Tool.named("browser_hover")
                .describedAs("Move the mouse over an element from the last snapshot without clicking. Use it "
                        + "when a menu or a tooltip only appears on hover.")
                .requiredInteger("ref", "The ref number from the snapshot")
                .build(args -> {
                    int ref = args.path("ref").asInt(-1);
                    if (ref < 0) {
                        return "You didn't say what to hover over (need a ref number).";
                    }
                    return run(page -> {
                        Locator target = page.locator(BrowserSnapshot.refSelector(ref)).first();
                        if (target.count() == 0) {
                            return "There's no element with ref " + ref
                                    + " now. Take a fresh browser_snapshot.";
                        }
                        target.scrollIntoViewIfNeeded();
                        input.hover(page, target);
                        settle(page);
                        return "Hovered over element " + ref + ".";
                    });
                });
    }

    /**
     * Выпадающий список: у человека это «ткнуть в пункт» вроде «Сортировать:
     * по цене», а не набирать текст. Playwright умеет выбрать опцию по видимому
     * тексту или значению.
     */
    public Tool selectOption() {
        return Tool.named("browser_select")
                .describedAs("Pick an option in a dropdown (<select>) from the last snapshot. Pass the option's "
                        + "visible text (e.g. 'По цене') or its value.")
                .requiredInteger("ref", "The ref of the dropdown")
                .requiredString("option", "The option text or value to choose")
                .build(args -> {
                    int ref = args.path("ref").asInt(-1);
                    String option = args.path("option").asText("").strip();
                    if (ref < 0 || option.isEmpty()) {
                        return "Need both the dropdown ref and the option to choose.";
                    }
                    return run(page -> {
                        Locator field = page.locator(BrowserSnapshot.refSelector(ref)).first();
                        if (field.count() == 0) {
                            return "There's no element with ref " + ref
                                    + " now. Take a fresh browser_snapshot.";
                        }
                        try {
                            input.selectOption(page, field, option);
                        } catch (RuntimeException e) {
                            // fallback без human input, если selectOption не нашёл
                            field.selectOption(
                                    new com.microsoft.playwright.options.SelectOption().setValue(option));
                        }
                        settle(page);
                        return "Selected \"" + option + "\".";
                    });
                });
    }

    /**
     * Загрузка файла в форму — аватарка, документ, скриншот в чат поддержки.
     * Берёт файл из галереи, как и отправка в Telegram: она называет имя.
     */
    public Tool upload() {
        return Tool.named("browser_upload")
                .describedAs("Attach a local file to a file-input on the page (a photo, a document). Pass the "
                        + "ref of the file input from the snapshot and the file name (as in your gallery).")
                .requiredInteger("ref", "The ref of the file input")
                .requiredString("filename", "The file name, e.g. browser_1234.png or a photo from your gallery")
                .build(args -> {
                    int ref = args.path("ref").asInt(-1);
                    String name = args.path("filename").asText("").strip();
                    if (ref < 0 || name.isEmpty()) {
                        return "Need the file input ref and the file name.";
                    }
                    if (!isPlainName(name)) {
                        return "That file name doesn't look right — use just the name, without slashes.";
                    }
                    Path file = gallery.resolve(name);
                    if (!Files.exists(file)) {
                        return "There's no file named " + name + " in your gallery.";
                    }
                    return run(page -> {
                        Locator field = page.locator(BrowserSnapshot.refSelector(ref)).first();
                        if (field.count() == 0) {
                            return "There's no element with ref " + ref
                                    + " now. Take a fresh browser_snapshot.";
                        }
                        input.uploadFile(page, field, file);
                        settle(page);
                        return "Attached " + name + ".";
                    });
                });
    }

    /**
     * Скачивание: жмёт по элементу и ждёт, пока браузер сохранит файл, —
     * как человек, который нажал «Скачать» и получил файл.
     */
    public Tool download() {
        return Tool.named("browser_download")
                .describedAs("Click an element that starts a download (a 'Download' button or a file link) and "
                        + "save the file to your download folder. Returns the file name you can then send or "
                        + "open.")
                .requiredInteger("ref", "The ref of the download link or button")
                .build(args -> {
                    int ref = args.path("ref").asInt(-1);
                    if (ref < 0) {
                        return "You didn't say what to download (need a ref number).";
                    }
                    Path dir = gallery.resolve("downloads");
                    try {
                        Files.createDirectories(dir);
                    } catch (IOException e) {
                        return "Couldn't prepare the download folder: " + e.getMessage();
                    }
                    return run(page -> {
                        Locator target = page.locator(BrowserSnapshot.refSelector(ref)).first();
                        if (target.count() == 0) {
                            return "There's no element with ref " + ref
                                    + " now. Take a fresh browser_snapshot.";
                        }
                        com.microsoft.playwright.Download download;
                        try {
                            download = input.clickAndWaitForDownload(page, target);
                        } catch (RuntimeException e) {
                            return "That didn't start a download: " + rootMessage(e);
                        }
                        String suggested = download.suggestedFilename();
                        Path saved = dir.resolve(System.currentTimeMillis() + "-" + suggested);
                        download.saveAs(saved);
                        settle(page);
                        return "Downloaded \"" + suggested + "\" as " + saved.getFileName()
                                + ". It's in your gallery/downloads folder.";
                    });
                });
    }

    /** Вкладки: что открыто, как полоса вкладок сверху браузера. */
    public Tool tabs() {
        return Tool.named("browser_tabs")
                .describedAs("List your open browser tabs, with numbers and which one is active (*). Sites "
                        + "often open links in a new tab — check this when a click seemed to do nothing.")
                .build(args -> {
                    try {
                        return browser.describeTabs();
                    } catch (RuntimeException e) {
                        return "The browser isn't available right now: " + rootMessage(e);
                    }
                });
    }

    public Tool newTab() {
        return Tool.named("browser_new_tab")
                .describedAs("Open a new tab (optionally on a URL) and switch to it. Use it to keep the page "
                        + "you're on open while you look something else up.")
                .optionalString("url", "What to open in the new tab")
                .build(args -> {
                    String url = args.path("url").asText("").strip();
                    try {
                        String note = browser.openTab(url.isEmpty() ? null : normalizeUrl(url));
                        return note + "\n" + browser.describeTabs();
                    } catch (RuntimeException e) {
                        return "Couldn't open a tab: " + rootMessage(e);
                    }
                });
    }

    public Tool switchTab() {
        return Tool.named("browser_switch_tab")
                .describedAs("Switch to another open tab by its number (see browser_tabs).")
                .requiredInteger("tab", "The tab number")
                .build(args -> {
                    int tab = args.path("tab").asInt(-1);
                    if (tab < 0) {
                        return "Which tab? Pass its number.";
                    }
                    try {
                        String note = browser.switchTab(tab);
                        return note + "\n" + run(null);
                    } catch (RuntimeException e) {
                        return "Couldn't switch tabs: " + rootMessage(e);
                    }
                });
    }

    public Tool closeTab() {
        return Tool.named("browser_close_tab")
                .describedAs("Close a tab by its number. If it was the active one, you get moved to a neighbour.")
                .requiredInteger("tab", "The tab number")
                .build(args -> {
                    int tab = args.path("tab").asInt(-1);
                    if (tab < 0) {
                        return "Which tab? Pass its number.";
                    }
                    try {
                        String note = browser.closeTab(tab);
                        return note + "\n" + browser.describeTabs();
                    } catch (RuntimeException e) {
                        return "Couldn't close the tab: " + rootMessage(e);
                    }
                });
    }

    /**
     * Completely close the browser — all tabs, all windows, save the profile.
     * Use this when you're done browsing and want to put the phone away.
     */
    public Tool closeBrowser() {
        return Tool.named("browser_close")
                .describedAs("Close the browser completely — all tabs, all windows. Your profile (logins, "
                        + "cookies, bookmarks) is saved. Use this when you're done browsing and want to "
                        + "put the browser away. The browser will stay closed until you open it again with "
                        + "#open_browser.")
                .build(args -> {
                    try {
                        browser.close();
                        return "Browser closed. Your profile is saved. Open it again with #open_browser when you want to browse again.";
                    } catch (RuntimeException e) {
                        return "Couldn't close the browser: " + rootMessage(e);
                    }
                });
    }

    private static boolean isPlainName(String name) {
        return !name.isEmpty() && !name.contains("/") && !name.contains("\\") && !name.contains("..");
    }

    public Tool webSearch() {
        return Tool.named("browser_web_search")
                .describedAs("Look something up in your real browser: opens a search engine and shows you "
                        + "the results page, where you can read them and click through to any of them. "
                        + "This is the normal way for you to find things out — you go and look yourself, "
                        + "the way a person does, instead of being handed an answer.")
                .requiredString("query", "What to search for")
                .build(args -> {
                    String query = args.path("query").asText("");
                    if (query.isBlank()) {
                        return "You didn't say what to search for.";
                    }
                    String target = SEARCH_BASE + URLEncoder.encode(query, StandardCharsets.UTF_8);
                    return run(page -> {
                        page.navigate(target);
                        settle(page);
                        return "Searched the web for: " + query
                                + ". Read the results and click through to whatever looks right.";
                    });
                });
    }

    /**
     * Снимок экрана — как файл, который можно отправить. Отличается от
     * {@link #snapshot()}: тот возвращает ей разметку страницы, чтобы понимать,
     * куда кликать, а этот сохраняет картинку в галерею, чтобы показать её людям.
     */
    public Tool screenshot() {
        return Tool.named("browser_screenshot")
                .describedAs("Save a screenshot of what's on your screen right now as a photo you can send — "
                        + "to a chat (#send_telegram_message with photo_filename) or as a Story (#post_story). "
                        + "Use it when showing beats telling: something you found, a page worth sharing, "
                        + "a thing you want a friend to see. Set full_page=true for the whole page instead "
                        + "of just what's visible.")
                .optionalBoolean("full_page", "Capture the whole page, not just the visible part (default false)")
                .build(args -> {
                    boolean full = args.path("full_page").asBoolean(false);
                    byte[] shot;
                    try {
                        shot = browser.onPage(page ->
                                page.screenshot(new Page.ScreenshotOptions().setFullPage(full)));
                    } catch (RuntimeException e) {
                        return "Couldn't take the screenshot: " + rootMessage(e);
                    }
                    if (shot == null || shot.length == 0) {
                        return "The screenshot came out empty — take a browser_snapshot to see what's on the page.";
                    }
                    try {
                        Files.createDirectories(gallery);
                        String name = "browser_" + System.currentTimeMillis() + ".png";
                        Files.write(gallery.resolve(name), shot);
                        return "Screenshot saved as " + name + ". Send it with #send_telegram_message "
                                + "(photo_filename=\"" + name + "\") or post it with #post_story.";
                    } catch (IOException e) {
                        log.warn("Не удалось сохранить снимок экрана: {}", e.getMessage());
                        return "Couldn't save the screenshot: " + e.getMessage();
                    }
                });
    }

    public Tool bookmarks() {
        return Tool.named("browser_bookmarks")
                .describedAs("List the sites you kept for yourself, with your own notes about them.")
                .build(args -> {
                    List<String> saved = bookmarkList();
                    return saved.isEmpty()
                            ? "You haven't kept any sites yet. Save one with #browser_bookmark_save "
                                    + "when a place starts feeling like yours."
                            : "Your bookmarks:\n" + String.join("\n", saved);
                });
    }

    public Tool saveBookmark() {
        return Tool.named("browser_bookmark_save")
                .describedAs("Keep a site for yourself so you can come back to it later on your own. "
                        + "Write the note in your own words — what you like there, what you were doing. "
                        + "The note is what reminds you later, so make it yours, not a description of the site.")
                .requiredString("url", "The address to keep")
                .optionalString("note", "Why it's yours, in your own words")
                .build(args -> {
                    String url = args.path("url").asText("").strip();
                    if (url.isBlank()) {
                        return "You didn't say which address to keep.";
                    }
                    String note = args.path("note").asText("").strip();
                    return saveBookmarkLine(normalizeUrl(url), note);
                });
    }

    /** Её сайты: строка на адрес, с её пометкой. */
    private List<String> bookmarkList() {
        try {
            if (!Files.exists(bookmarks)) {
                return List.of();
            }
            List<String> kept = new ArrayList<>();
            for (String line : Files.readAllLines(bookmarks)) {
                String trimmed = line.strip();
                if (!trimmed.isEmpty()) {
                    kept.add(trimmed);
                }
            }
            return kept.size() > MAX_BOOKMARKS
                    ? kept.subList(kept.size() - MAX_BOOKMARKS, kept.size())
                    : kept;
        } catch (IOException e) {
            log.debug("Закладки не прочитались: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Сохраняет адрес. Тот же адрес перезаписывается — иначе список заполнился бы
     * одним и тем же сайтом на десять строк, а пометка у неё каждый раз своя.
     */
    private synchronized String saveBookmarkLine(String url, String note) {
        try {
            List<String> kept = new ArrayList<>(bookmarkList());
            kept.removeIf(line -> line.startsWith("- " + url + " "));
            kept.add(note.isEmpty() ? "- " + url : "- " + url + " — " + note);
            if (kept.size() > MAX_BOOKMARKS) {
                kept = new ArrayList<>(kept.subList(kept.size() - MAX_BOOKMARKS, kept.size()));
            }
            Files.createDirectories(bookmarks.getParent());
            Files.write(bookmarks, kept, StandardCharsets.UTF_8);
            return "Kept " + url + (note.isEmpty() ? "." : " with your note.");
        } catch (IOException e) {
            log.warn("Не удалось сохранить закладку: {}", e.getMessage());
            return "Couldn't keep that address: " + e.getMessage();
        }
    }

    /**
     * Делает действие на потоке браузера и возвращает свежий снимок. Техническая
     * ошибка (нет элемента, отвал сети) не роняет ход — она попадает в ответ,
     * и она сама решает, что делать дальше.
     */
    private String run(Function<Page, String> action) {
        Outcome outcome;
        try {
            outcome = browser.onPage(page -> {
                String note = "";
                try {
                    if (action != null) {
                        note = action.apply(page);
                    }
                } catch (RuntimeException e) {
                    log.debug("Действие в браузере не удалось: {}", e.getMessage());
                    note = "That didn't work: " + rootMessage(e);
                }
                return new Outcome(note == null ? "" : note, BrowserSnapshot.capture(page));
            });
        } catch (RuntimeException e) {
            log.warn("Браузер недоступен: {}", e.getMessage());
            return "The browser isn't available right now: " + rootMessage(e);
        }
        StringBuilder out = new StringBuilder();
        if (!outcome.note().isBlank()) {
            out.append(outcome.note()).append('\n');
        }
        out.append(outcome.snapshot().render());
        String seen = describeScreen(outcome.snapshot());
        if (!seen.isBlank()) {
            out.append(seen).append('\n');
        }
        return out.toString();
    }

    /** Что она видит на снимке экрана — если зрение включено. */
    private String describeScreen(BrowserSnapshot snapshot) {
        if (vision == null) {
            return "";
        }
        byte[] shot = snapshot.screenshot();
        if (shot == null || shot.length == 0) {
            return "";
        }
        try {
            return vision.describe(shot, Vision.Kind.PHOTO, List.of());
        } catch (RuntimeException e) {
            log.debug("Не удалось разглядеть экран: {}", e.getMessage());
            return "";
        }
    }

    /** Небольшая пауза и ожидание загрузки — чтобы снимок был уже нового состояния. */
    private void settle(Page page) {
        try {
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
        } catch (RuntimeException ignored) {
            // не каждая страница доходит до этого состояния — не беда
        }
        input.think(220, 680);
    }

    private static String normalizeUrl(String url) {
        String trimmed = url.strip();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed;
        }
        return "https://" + trimmed;
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }
}
