package ru.rainedev.raine.tiktok;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.BoundingBox;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.Cookie;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.browser.HumanInput;

/**
 * TikTok: адреса, селекторы и приёмы — всё, что знает, как устроен этот сайт.
 * <p>
 * Селекторы TikTok ({@code data-e2e}) меняются часто, поэтому на каждое место
 * здесь несколько вариантов и поиск идёт по первому видимому. Если не нашёлся
 * ни один — инструмент честно скажет ей посмотреть страницу и кликнуть по
 * номеру через обычные {@code browser_*}, так что поломка одного селектора не
 * ломает всё.
 * <p>
 * Методы, работающие со страницей, вызываются на потоке браузера (через
 * {@code Browser#onPage}); скачивание ролика — наоборот, вне его, чтобы не
 * держать браузер занятым на сети.
 * <p>
 * <b>Риск.</b> Автоматизация TikTok нарушает его правила; аккаунт могут
 * ограничить или забанить. Поэтому действия редкие и с пределами
 * (см. {@link TikTokLimits}).
 */
public final class TikTok {

    private static final Logger log = LoggerFactory.getLogger(TikTok.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final String FEED_URL = "https://www.tiktok.com/foryou";
    public static final String SEARCH_URL = "https://www.tiktok.com/search?q=";

    /** Строка браузера для скачивания — как у живого Chromium. */
    private static final String DOWNLOAD_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/124.0.0.0 Safari/537.36";

    /** Сколько ждать скачивания одного ролика. */
    private static final long DOWNLOAD_TIMEOUT_SECONDS = 150;

    /** Меньше этого — не ролик, а огрызок или страница ошибки. */
    private static final long MIN_VIDEO_BYTES = 20_000;

    private static final Pattern HASHTAG = Pattern.compile("#[\\p{L}\\p{N}_]+");
    private static final Pattern VIDEO_ID = Pattern.compile("/video/(\\d+)");

    /** Открытая ссылка/ник в Telegram: t.me/name, telegram.me/name, @name. */
    private static final Pattern TELEGRAM_INVITE =
            Pattern.compile("(?i)(?:https?://)?t(?:elegram)?\\.me/(?:\\+|joinchat/)([A-Za-z0-9_-]+)");
    private static final Pattern TELEGRAM_PUBLIC =
            Pattern.compile("(?i)(?:https?://)?t(?:elegram)?\\.me/([A-Za-z][A-Za-z0-9_]{2,})");
    private static final Pattern TELEGRAM_AT = Pattern.compile("(?<![\\w@])@([A-Za-z][A-Za-z0-9_]{2,})");
    /** Признак, что рядом с @ник действительно говорят про Telegram. */
    private static final Pattern TELEGRAM_WORD =
            Pattern.compile("(?i)(?:^|[^\\p{L}])(?:тг|tg|телег|телеграм|telegram)");

    /**
     * Найденный способ попасть в чей-то Telegram.
     *
     * @param handle публичный ник («durov») или пусто
     * @param invite код приглашения («+AbCdEf») или пусто
     */
    public record TelegramLink(String handle, String invite) {
        public boolean isInvite() {
            return invite != null && !invite.isBlank();
        }

        /** Телеграм-адрес, который можно передать в join/check. */
        public String url() {
            return "https://t.me/" + (isInvite() ? invite : handle);
        }

        public String describe() {
            return isInvite()
                    ? url() + " (invite link)"
                    : "@" + handle + " (" + url() + ")";
        }
    }

    /**
     * Вытаскивает телеграм-ссылки из текста bio или подписи к ролику.
     * {@code t.me/...} берём всегда — это однозначно Telegram. {@code @ник}
     * берём только если рядом есть слово «тг/телеграм/tg»: иначе это
     * упоминание другого тиктокера, а не канал.
     */
    public static List<TelegramLink> telegramLinks(String text) {
        List<TelegramLink> found = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return found;
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        Matcher invite = TELEGRAM_INVITE.matcher(text);
        while (invite.find() && found.size() < 5) {
            String code = "+" + invite.group(1);
            if (seen.add(code)) {
                found.add(new TelegramLink("", code));
            }
        }
        Matcher pub = TELEGRAM_PUBLIC.matcher(text);
        while (pub.find() && found.size() < 5) {
            String handle = pub.group(1);
            if (isReservedPath(handle)) {
                continue;
            }
            if (seen.add(handle)) {
                found.add(new TelegramLink(handle, ""));
            }
        }
        if (TELEGRAM_WORD.matcher(text).find()) {
            Matcher at = TELEGRAM_AT.matcher(text);
            while (at.find() && found.size() < 5) {
                String handle = at.group(1);
                if (seen.add(handle)) {
                    found.add(new TelegramLink(handle, ""));
                }
            }
        }
        return found;
    }

    /** Пути внутри t.me, которые не являются чьим-то ником. */
    private static boolean isReservedPath(String handle) {
        String low = handle.toLowerCase();
        return low.equals("s") || low.equals("c") || low.equals("addstickers")
                || low.equals("addemoji") || low.equals("joinchat") || low.equals("share");
    }

    private static final List<String> LIKE = List.of(
            "[data-e2e=\"like-icon\"]", "[data-e2e=\"browse-like\"]", "button[data-e2e=\"like-icon\"]");
    private static final List<String> COMMENT = List.of(
            "[data-e2e=\"comment-icon\"]", "[data-e2e=\"browse-comment\"]");
    private static final List<String> COMMENT_PANEL = List.of(
            "[data-e2e=\"comment-panel\"]", "div[class*=\"DivCommentContainer\"]",
            "div[class*=\"DivCommentListContainer\"]");
    private static final List<String> COMMENT_CLOSE = List.of(
            "[data-e2e=\"comment-close\"]", "div[data-e2e=\"comment-panel\"] button[aria-label*=\"Close\" i]",
            "div[class*=\"DivCommentContainer\"] button[aria-label*=\"Close\" i]",
            "button[aria-label*=\"Закрыть\" i]");
    private static final List<String> COMMENT_VIEW_MORE = List.of(
            "[data-e2e=\"comment-view-more-1\"]", "[data-e2e=\"comment-view-more\"]",
            "span[data-e2e*=\"view-more\"]");
    /**
     * Поле ответа на конкретный комментарий — внутри его карточки. TikTok
     * открывает его после нажатия «Ответить», и оно не то же самое, что общее
     * поле комментария к ролику.
     */
    private static final List<String> REPLY_INPUT = List.of(
            "[data-raine-cref] [contenteditable=\"true\"]",
            "[data-raine-cref] [data-e2e^=\"comment-input\"]",
            "[data-raine-cref] textarea");
    private static final List<String> COMMENT_INPUT = List.of(
            "[data-e2e=\"comment-input\"]", "div[data-e2e=\"comment-input\"]",
            "[data-e2e=\"comment-panel\"] [contenteditable=\"true\"]");
    private static final List<String> COMMENT_POST = List.of(
            "[data-e2e=\"comment-post\"]", "button[data-e2e=\"comment-post\"]");
    private static final List<String> REPOST = List.of(
            "[data-e2e=\"repost-icon\"]", "[data-e2e=\"browse-repost\"]", "[data-e2e=\"share-icon\"]");
    private static final List<String> AUTHOR = List.of(
            "[data-e2e=\"video-author-uniqueid\"]", "[data-e2e=\"browse-username\"]",
            "span[data-e2e=\"search_top-user\"] a");
    private static final List<String> CAPTION = List.of(
            "[data-e2e=\"browse-caption\"]", "[data-e2e=\"caption\"]", "[data-e2e=\"video-desc\"]");
    /**
     * Поля профиля. Ссылку на свой Telegram авторы обычно пишут в bio —
     * «tg: @channel», «t.me/channel», иногда приглашением «t.me/+abc».
     * Ник в TikTok и ник в Telegram часто РАЗНЫЕ, поэтому угадывать по
     * первому бесполезно: нужен текст bio.
     */
    private static final List<String> PROFILE_NAME = List.of(
            "[data-e2e=\"user-title\"]", "h1[data-e2e=\"user-title\"]", "h2[data-e2e=\"user-title\"]");
    private static final List<String> PROFILE_BIO = List.of(
            "[data-e2e=\"user-bio\"]", "h2[data-e2e=\"user-bio\"]", "[data-e2e=\"user-desc\"]");
    private static final List<String> PROFILE_FOLLOWERS = List.of(
            "[data-e2e=\"followers-count\"]", "strong[data-e2e=\"followers-count\"]");
    private static final List<String> PROFILE_LIKES = List.of(
            "[data-e2e=\"likes-count\"]", "strong[data-e2e=\"likes-count\"]");
    private static final List<String> LOGGED_IN = List.of(
            "[data-e2e=\"profile-icon\"]", "[data-e2e=\"inbox-icon\"]", "img[data-e2e=\"profile-icon\"]");
    private static final List<String> LOGGED_OUT = List.of(
            "[data-e2e=\"top-login-button\"]", "button[data-e2e=\"top-login-button\"]");

    /** Все признаки входа одним селектором — по нему ждём отрисовки шапки. */
    private static final String LOGGED_IN_ANY =
            "[data-e2e=\"profile-icon\"], [data-e2e=\"inbox-icon\"]";

    /** Сколько даём SPA нарисовать шапку: меньше — ложное «не вошёл». */
    private static final double LOGIN_MARK_TIMEOUT_MS = 12_000;

    /** Куки, наличие которых и есть вход. Имена стабильнее, чем разметка. */
    private static final List<String> SESSION_COOKIES = List.of("sessionid", "sid_tt", "sessionid_ss");
    private static final List<String> DM_OPEN = List.of(
            "[data-e2e=\"message-button\"]", "a[href*=\"/messages\"]", "[data-e2e=\"browse-message\"]");
    private static final List<String> DM_INPUT = List.of(
            "[data-e2e=\"conversation-input\"]", "[data-e2e=\"im-text-input\"]", "[contenteditable=\"true\"]");
    private static final List<String> DM_SEND = List.of(
            "[data-e2e=\"conversation-send\"]", "button[data-e2e=\"conversation-send\"]");
    private static final String DM_FILE_INPUT = "input[type=\"file\"]";

    private static final String VIDEO_SRC_SCRIPT =
            "(() => { const v = document.querySelector('video'); "
                    + "return v ? (v.currentSrc || v.src || '') : ''; })()";

    /**
     * Чтение комментариев: обход карточек, нескольким вариантам селекторов
     * на каждое поле, и попутная разметка номеров — {@code data-raine-cref},
     * по ним потом кликают «Ответить». Номера ставятся только на те карточки,
     * что попали в выдачу, поэтому они всегда сплошные.
     */
    private static final String READ_COMMENTS_SCRIPT = """
            ((limit) => {
              const pick = (root, sels) => {
                for (const s of sels) {
                  try { const el = root.querySelector(s); if (el) return el; } catch (e) {}
                }
                return null;
              };
              const nodes = Array.from(document.querySelectorAll(
                '[data-e2e="comment-level-1"], [data-e2e="comment-level-2"], \
            div[class*="DivCommentItemContainer"]'));
              const out = [];
              for (const node of nodes) {
                if (out.length >= limit) break;
                const rect = node.getBoundingClientRect();
                if (rect.width < 40 || rect.height < 12) continue;
                const authorEl = pick(node, ['a[data-e2e^="comment-username"]',
                  '[data-e2e^="comment-username"]', 'a[href^="/@"]']);
                const textEl = pick(node, ['p[data-e2e="comment-content"]',
                  '[data-e2e="comment-content"]', 'span[data-e2e="comment-content"]']);
                const likeEl = pick(node, ['[data-e2e^="comment-like-count"]',
                  'span[data-e2e="digg-count"]']);
                const timeEl = pick(node, ['[data-e2e="comment-time"]',
                  'span[class*="SpanCommentTime"]']);
                const author = authorEl ? (authorEl.innerText || '').replace(/^@/, '').trim() : '';
                let text = textEl ? (textEl.innerText || '') : '';
                if (!text.trim()) text = node.innerText || '';
                if (author) {
                  const at = text.indexOf(author);
                  if (at >= 0) text = text.slice(at + author.length);
                }
                text = text.replace(/\\s+/g, ' ').trim();
                if (!text && !author) continue;
                const ref = out.length + 1;
                node.setAttribute('data-raine-cref', String(ref));
                out.push({ref: ref, author: author, text: text.slice(0, 320),
                  likes: likeEl ? (likeEl.innerText || '').trim() : '',
                  time: timeEl ? (timeEl.innerText || '').trim() : '',
                  level: (node.getAttribute('data-e2e') || '').indexOf('level-2') >= 0 ? 2 : 1});
              }
              return JSON.stringify(out);
            })(%d)""";

    /**
     * Помечает кнопку «Ответить» внутри карточки комментария. Ищем по тексту,
     * а не по селектору: TikTok меняет {@code data-e2e} чаще, чем слова на
     * кнопке, и текст ещё и на языке интерфейса — поэтому список слов.
     */
    private static final String MARK_REPLY_SCRIPT = """
            ((ref) => {
              const node = document.querySelector('[data-raine-cref="' + ref + '"]');
              if (!node) return '';
              const words = ['reply', 'ответить', 'ответ', 'відповісти'];
              const els = Array.from(node.querySelectorAll('p, span, div, button'));
              for (const el of els) {
                if (el.children.length > 0) continue;
                const t = (el.innerText || '').trim().toLowerCase();
                if (!t || t.length > 24 || /\\d/.test(t)) continue;
                if (words.some(w => t === w || t.startsWith(w))) {
                  el.setAttribute('data-raine-reply', '1');
                  el.scrollIntoView({block: 'center'});
                  return 'ok';
                }
              }
              return '';
            })(%d)""";

    /** Помеченная кнопка «Ответить» — по ней кликают обычным живым кликом. */
    private static final String REPLY_MARK = "[data-raine-reply='1']";

    private TikTok() {}

    /** То, что видно о текущем ролике. */
    public record VideoInfo(String url, String author, String caption, String videoSrc, List<String> hashtags) {
        public String describe() {
            StringBuilder out = new StringBuilder();
            out.append("<tiktok_video url=\"").append(url == null ? "" : url).append("\">\n");
            if (author != null && !author.isBlank()) {
                out.append("author: @").append(author.replaceFirst("^@", "")).append('\n');
            }
            if (caption != null && !caption.isBlank()) {
                out.append("caption: ").append(caption.strip()).append('\n');
            }
            if (hashtags != null && !hashtags.isEmpty()) {
                out.append("hashtags: ").append(String.join(" ", hashtags)).append('\n');
            }
            appendTelegram(out, telegramLinks(caption));
            out.append("</tiktok_video>\n");
            return out.toString();
        }
    }

    /** Что видно в профиле автора — в первую очередь bio, где обычно и лежит Telegram. */
    public record ProfileInfo(String handle, String name, String bio, String followers, String likes,
                              List<TelegramLink> telegramLinks) {
        public String describe() {
            StringBuilder out = new StringBuilder();
            out.append("<tiktok_profile handle=\"@").append(handle == null ? "" : handle).append("\">\n");
            if (name != null && !name.isBlank()) {
                out.append("name: ").append(name.strip()).append('\n');
            }
            if (bio != null && !bio.isBlank()) {
                out.append("bio: ").append(bio.strip().replace("\n", " ")).append('\n');
            }
            if (followers != null && !followers.isBlank()) {
                out.append("followers: ").append(followers.strip()).append('\n');
            }
            if (likes != null && !likes.isBlank()) {
                out.append("likes: ").append(likes.strip()).append('\n');
            }
            if (telegramLinks == null || telegramLinks.isEmpty()) {
                out.append("telegram: none found in the bio. Their TikTok and Telegram handles "
                        + "are often different — don't guess the nickname, look for a link.\n");
            } else {
                out.append("telegram they left in their bio:\n");
                for (TelegramLink link : telegramLinks) {
                    out.append("  - ").append(link.describe()).append('\n');
                }
            }
            out.append("</tiktok_profile>\n");
            return out.toString();
        }
    }

    private static void appendTelegram(StringBuilder out, List<TelegramLink> links) {
        if (links == null || links.isEmpty()) {
            return;
        }
        out.append("telegram mentioned here:\n");
        for (TelegramLink link : links) {
            out.append("  - ").append(link.describe()).append('\n');
        }
    }

    /** Вошли ли в аккаунт. Без входа лента и действия недоступны. */
    public static boolean loggedIn(Page page) {
        if (find(page, LOGGED_OUT).isPresent()) {
            return false;
        }
        // TikTok — тяжёлый SPA: на DOMCONTENTLOADED шапки с иконкой профиля ещё
        // нет, и проверка давала ложное «не вошёл». Даём разметке появиться.
        try {
            page.waitForSelector(LOGGED_IN_ANY, new Page.WaitForSelectorOptions()
                    .setState(com.microsoft.playwright.options.WaitForSelectorState.VISIBLE)
                    .setTimeout(LOGIN_MARK_TIMEOUT_MS));
            return true;
        } catch (RuntimeException e) {
            log.debug("Не дождался признаков входа: {}", e.getMessage());
            return find(page, LOGGED_IN).isPresent();
        }
    }

    /**
     * Вошли ли в аккаунт — по сессионным кукам. Надёжнее разметки: кука есть
     * сразу, а страница может ещё рисоваться или не показать шапку вовсе.
     */
    public static boolean hasSession(BrowserContext context) {
        try {
            for (Cookie cookie : context.cookies("https://www.tiktok.com")) {
                if (SESSION_COOKIES.contains(cookie.name)
                        && cookie.value != null && !cookie.value.isBlank()) {
                    return true;
                }
            }
        } catch (RuntimeException e) {
            log.debug("Не удалось посмотреть куки TikTok: {}", e.getMessage());
        }
        return false;
    }

    public static void openFeed(Page page) {
        page.navigate(FEED_URL);
        try {
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(30_000));
        } catch (RuntimeException e) {
            log.warn("Feed load timeout: {}", e.getMessage());
        }
        // Check for "page unavailable" error page
        if (isErrorPage(page)) {
            throw new RuntimeException("TikTok returned error page (unavailable/rate limited)");
        }
    }

    static boolean isErrorPage(Page page) {
        String content = page.content();
        return content.contains("страница недоступна") || content.contains("page unavailable") ||
               content.contains("попробуйте позже") || content.contains("try again later") ||
               content.contains("Too Many Requests") || content.contains("429") ||
               content.contains("captcha") || content.contains("security check");
    }

    public static void search(Page page, String query) {
        page.navigate(SEARCH_URL + java.net.URLEncoder.encode(query, StandardCharsets.UTF_8));
    }

    public static void openVideo(Page page, String url) {
        page.navigate(url);
    }

    /** Следующий ролик в ленте — как swipe вверх. */
    public static void next(Page page, HumanInput input) {
        input.press(page, "ArrowDown");
    }

    /** Лайк. @return удалось ли найти и нажать кнопку. */
    public static boolean like(Page page, HumanInput input) {
        Optional<Locator> button = find(page, LIKE);
        if (button.isEmpty()) {
            return false;
        }
        input.click(page, button.get());
        return true;
    }

    /** Репост в свою ленту. @return удалось ли найти и нажать. */
    public static boolean repost(Page page, HumanInput input) {
        Optional<Locator> button = find(page, REPOST);
        if (button.isEmpty()) {
            return false;
        }
        input.click(page, button.get());
        return true;
    }

    /**
     * Комментарий: открывает панель, печатает по-человечески, отправляет.
     *
     * @return получилось ли оставить комментарий
     */
    public static boolean comment(Page page, HumanInput input, String text) {
        Optional<Locator> opener = find(page, COMMENT);
        if (opener.isPresent()) {
            input.click(page, opener.get());
            input.think(400, 900);
        }
        Optional<Locator> field = find(page, COMMENT_INPUT);
        if (field.isEmpty()) {
            return false;
        }
        input.typeInto(page, field.get(), text);
        Optional<Locator> post = find(page, COMMENT_POST);
        if (post.isPresent()) {
            input.click(page, post.get());
        } else {
            input.press(page, "Enter");
        }
        return true;
    }

    /**
     * Один комментарий под роликом. {@code ref} — номер, по нему отвечают;
     * {@code level} 2 значит «ответ на комментарий», а не комментарий верхнего
     * уровня.
     */
    public record Comment(int ref, String author, String text, String likes, String time, int level) {

        /** Строка для модели: кто, что, сколько лайков, насколько глубоко в ветке. */
        public String render() {
            StringBuilder out = new StringBuilder();
            out.append('[').append(ref).append("] ");
            if (level > 1) {
                out.append("  ↳ ");
            }
            if (author != null && !author.isBlank()) {
                out.append('@').append(author).append(": ");
            }
            out.append(text == null ? "" : text);
            String meta = String.join(" · ", java.util.stream.Stream.of(
                            likes == null || likes.isBlank() ? null : likes + " likes",
                            time == null || time.isBlank() ? null : time)
                    .filter(part -> part != null).toList());
            if (!meta.isBlank()) {
                out.append("  (").append(meta).append(')');
            }
            return out.toString();
        }
    }

    /** Открыта ли сейчас панель комментариев. */
    public static boolean commentsOpen(Page page) {
        return find(page, COMMENT_PANEL).isPresent();
    }

    /**
     * Открывает панель комментариев, если она ещё не открыта.
     *
     * @return удалось ли (панель уже была открыта — тоже успех)
     */
    public static boolean openComments(Page page, HumanInput input) {
        if (commentsOpen(page)) {
            return true;
        }
        Optional<Locator> opener = find(page, COMMENT);
        if (opener.isEmpty()) {
            return false;
        }
        input.click(page, opener.get());
        input.think(700, 1500);
        return commentsOpen(page);
    }

    /**
     * Закрывает панель комментариев — вернуться к ролику.
     *
     * @return действительно ли панель закрылась: если кнопка не нашлась и
     *         Escape не сработал, она честно скажет и кликнет сама через
     *         {@code browser_snapshot}
     */
    public static boolean closeComments(Page page, HumanInput input) {
        if (!commentsOpen(page)) {
            return true;
        }
        Optional<Locator> close = find(page, COMMENT_CLOSE);
        if (close.isPresent()) {
            input.click(page, close.get());
        } else {
            input.press(page, "Escape");
        }
        input.think(400, 900);
        return !commentsOpen(page);
    }

    /**
     * Читает комментарии, которые сейчас видны. Попутно проставляет им номера,
     * чтобы на конкретный можно было ответить.
     */
    public static List<Comment> readComments(Page page, int limit) {
        List<Comment> comments = new ArrayList<>();
        try {
            Object result = page.evaluate(READ_COMMENTS_SCRIPT.formatted(Math.max(1, limit)));
            JsonNode array = MAPPER.readTree(result == null ? "[]" : result.toString());
            if (!array.isArray()) {
                return comments;
            }
            for (JsonNode node : array) {
                String text = node.path("text").asText("");
                String author = node.path("author").asText("");
                if (text.isBlank() && author.isBlank()) {
                    continue;
                }
                comments.add(new Comment(
                        node.path("ref").asInt(comments.size() + 1),
                        author,
                        text,
                        node.path("likes").asText(""),
                        node.path("time").asText(""),
                        node.path("level").asInt(1)));
            }
        } catch (RuntimeException | IOException e) {
            log.debug("Не удалось прочитать комментарии: {}", e.getMessage());
        }
        return comments;
    }

    /**
     * Листает панель комментариев: курсор сначала наводится на неё, иначе
     * колесо прокрутит страницу под ней, а не список.
     */
    public static void scrollComments(Page page, HumanInput input, int delta) {
        find(page, COMMENT_PANEL).ifPresent(panel -> {
            BoundingBox box = panel.boundingBox();
            if (box != null) {
                page.mouse().move(box.x + box.width / 2, box.y + box.height / 2);
            }
        });
        input.think(200, 600);
        input.scroll(page, delta);
    }

    /**
     * Ответ на конкретный комментарий: находим кнопку «Ответить» в его карточке,
     * жмём, печатаем по-человечески, отправляем.
     *
     * @param ref номер комментария из {@link #readComments}
     * @return удалось ли отправить
     */
    public static boolean replyTo(Page page, HumanInput input, int ref, String text) {
        Object marked;
        try {
            marked = page.evaluate(MARK_REPLY_SCRIPT.formatted(ref));
        } catch (RuntimeException e) {
            log.debug("Не нашёл комментарий {}: {}", ref, e.getMessage());
            return false;
        }
        if (marked == null || !"ok".equals(marked.toString())) {
            return false;
        }
        Locator reply = page.locator(REPLY_MARK).first();
        try {
            if (reply.count() == 0) {
                return false;
            }
        } catch (RuntimeException e) {
            return false;
        }
        input.click(page, reply);
        input.think(600, 1400);

        // поле ответа живёт в карточке комментария; если его там не видно,
        // пробуем общее поле — некоторые раскладки открывают именно его
        Optional<Locator> field = find(page, REPLY_INPUT);
        if (field.isEmpty()) {
            field = find(page, COMMENT_INPUT);
        }
        if (field.isEmpty()) {
            return false;
        }
        input.typeInto(page, field.get(), text);
        Optional<Locator> post = find(page, COMMENT_POST);
        if (post.isPresent()) {
            input.click(page, post.get());
        } else {
            input.press(page, "Enter");
        }
        return true;
    }

    /** Адрес чьей-то страницы по нику. */
    public static String profileUrl(String handle) {
        return "https://www.tiktok.com/@" + (handle == null ? "" : handle.strip().replaceFirst("^@", ""));
    }

    /**
     * Открывает профиль автора и читает, что там написано: имя, bio, счётчики.
     * Главная ценность — bio: именно там авторы оставляют свой Telegram, и ник
     * там почти никогда не совпадает с тиктоковским. Вызывать на потоке браузера.
     */
    public static ProfileInfo readProfile(Page page, String handle) {
        String clean = handle == null ? "" : handle.strip().replaceFirst("^@", "");
        page.navigate(profileUrl(clean));
        String name = text(page, PROFILE_NAME);
        String bio = text(page, PROFILE_BIO);
        return new ProfileInfo(clean, name, bio,
                text(page, PROFILE_FOLLOWERS), text(page, PROFILE_LIKES),
                telegramLinks(bio));
    }

    /**
     * Личное сообщение другу: заходит на страницу, открывает диалог, при
     * надобности прикладывает ролик, печатает и отправляет. Всё по-человечески,
     * с паузами на загрузку.
     *
     * @param video ролик, который приложить, или {@code null}
     * @return удалось ли отправить
     */
    public static boolean dm(Page page, HumanInput input, String handle, String text, Path video) {
        page.navigate(profileUrl(handle));
        input.think(1400, 2400);
        Optional<Locator> opener = find(page, DM_OPEN);
        if (opener.isEmpty()) {
            return false;
        }
        input.click(page, opener.get());
        input.think(900, 1700);
        if (video != null) {
            try {
                Locator file = page.locator(DM_FILE_INPUT).first();
                if (file.count() > 0) {
                    file.setInputFiles(video);
                    input.think(1500, 3000);
                }
            } catch (RuntimeException e) {
                log.debug("Не удалось приложить ролик в личку: {}", e.getMessage());
            }
        }
        Optional<Locator> field = find(page, DM_INPUT);
        if (field.isEmpty()) {
            return false;
        }
        input.typeInto(page, field.get(), text);
        Optional<Locator> send = find(page, DM_SEND);
        if (send.isPresent()) {
            input.click(page, send.get());
        } else {
            input.press(page, "Enter");
        }
        return true;
    }

    /** Читает, что за ролик сейчас на экране. Вызывать на потоке браузера. */
    public static VideoInfo readVideo(Page page) {
        String url = quietly(page::url);
        String author = text(page, AUTHOR);
        String caption = text(page, CAPTION);
        String src = "";
        try {
            Object result = page.evaluate(VIDEO_SRC_SCRIPT);
            src = result == null ? "" : result.toString();
        } catch (RuntimeException e) {
            log.debug("Не удалось достать адрес ролика: {}", e.getMessage());
        }
        return new VideoInfo(url, author, caption, src, hashtags(caption));
    }

    /**
     * Скачивает ролик: сначала yt-dlp (надёжнее, обходит защиту CDN), при его
     * отсутствии или неудаче — напрямую по адресу из плеера.
     *
     * @return путь к файлу или пусто, если не вышло
     */
    public static Optional<Path> download(VideoInfo info, Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.warn("Не удалось создать папку для роликов {}: {}", dir, e.getMessage());
            return Optional.empty();
        }
        Path target = dir.resolve(fileName(info.url()) + ".mp4");
        if (ytDlp(info.url(), target)) {
            return Optional.of(target);
        }
        if (direct(info.videoSrc(), target)) {
            return Optional.of(target);
        }
        return Optional.empty();
    }

    /** Пробует скачать через yt-dlp. @return успех. */
    private static boolean ytDlp(String url, Path target) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            Process process = new ProcessBuilder(
                    "yt-dlp", "--no-playlist", "-f", "mp4/best[ext=mp4]/best",
                    "--no-part", "-o", target.toString(), url)
                    .redirectErrorStream(true)
                    .start();
            StringBuilder output = new StringBuilder();
            Thread drainer = Thread.ofVirtual().start(() -> {
                try (var in = process.getInputStream()) {
                    output.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                    // процесс мог закрыться раньше — не важно
                }
            });
            boolean finished = process.waitFor(DOWNLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.debug("yt-dlp не уложился в {} с", DOWNLOAD_TIMEOUT_SECONDS);
                return false;
            }
            drainer.join(2000);
            boolean ok = process.exitValue() == 0 && exists(target);
            if (!ok) {
                log.debug("yt-dlp не скачал ({}): {}", process.exitValue(),
                        output.toString().strip().lines().reduce((a, b) -> b).orElse(""));
            }
            return ok;
        } catch (IOException e) {
            // yt-dlp не установлен — это не ошибка, есть запасной путь
            log.debug("yt-dlp недоступен: {}", e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Запасной путь: скачать mp4 напрямую из плеера. */
    private static boolean direct(String videoSrc, Path target) {
        if (videoSrc == null || !videoSrc.startsWith("http")) {
            return false;
        }
        try {
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(videoSrc))
                    .header("User-Agent", DOWNLOAD_USER_AGENT)
                    .header("Referer", "https://www.tiktok.com/")
                    .timeout(Duration.ofSeconds(90))
                    .GET()
                    .build();
            HttpResponse<Path> response = http.send(request, HttpResponse.BodyHandlers.ofFile(target));
            return response.statusCode() / 100 == 2 && exists(target);
        } catch (IOException e) {
            log.debug("Не удалось скачать ролик напрямую: {}", e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (RuntimeException e) {
            log.debug("Не удалось скачать ролик напрямую: {}", e.getMessage());
            return false;
        }
    }

    private static boolean exists(Path file) {
        try {
            return Files.exists(file) && Files.size(file) >= MIN_VIDEO_BYTES;
        } catch (IOException e) {
            return false;
        }
    }

    /** Первый видимый элемент из списка кандидатов. */
    static Optional<Locator> find(Page page, List<String> candidates) {
        for (String selector : candidates) {
            try {
                Locator locator = page.locator(selector).first();
                if (locator.count() > 0 && locator.boundingBox() != null) {
                    return Optional.of(locator);
                }
            } catch (RuntimeException e) {
                // селектор не подошёл этой странице — пробуем следующий
            }
        }
        return Optional.empty();
    }

    private static String text(Page page, List<String> candidates) {
        return find(page, candidates)
                .map(locator -> {
                    try {
                        String value = locator.textContent();
                        return value == null ? "" : value.strip();
                    } catch (RuntimeException e) {
                        return "";
                    }
                })
                .orElse("");
    }

    private static List<String> hashtags(String caption) {
        List<String> tags = new ArrayList<>();
        if (caption == null) {
            return tags;
        }
        Matcher matcher = HASHTAG.matcher(caption);
        while (matcher.find() && tags.size() < 20) {
            tags.add(matcher.group());
        }
        return tags;
    }

    /** Имя файла из адреса ролика: по его номеру, а нет — по времени. */
    private static String fileName(String url) {
        if (url != null) {
            Matcher matcher = VIDEO_ID.matcher(url);
            if (matcher.find()) {
                return "tiktok_" + matcher.group(1);
            }
        }
        return "tiktok_" + System.currentTimeMillis();
    }

    private static String quietly(java.util.function.Supplier<String> action) {
        try {
            String value = action.get();
            return value == null ? "" : value;
        } catch (RuntimeException e) {
            return "";
        }
    }
}
