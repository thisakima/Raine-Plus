package ru.rainedev.raine.tiktok;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.browser.Browser;
import ru.rainedev.raine.browser.HumanInput;
import ru.rainedev.raine.config.Config;
import ru.rainedev.raine.core.Tool;
import ru.rainedev.raine.vision.VideoFrames;
import ru.rainedev.raine.vision.Vision;

/**
 * Инструменты TikTok поверх общего тела браузера.
 * <p>
 * Она открывает ленту, листает, смотрит ролики по-настоящему (читает подпись,
 * скачивает короткий кусок, разглядывает кадры и слушает звук), лайкает,
 * комментирует, репостит, ищет то, что ей лично интересно, скачивает и
 * отправляет друзьям — в личку TikTok или, через Telegram, если так удобнее.
 * <p>
 * Каждое действие по-человечески (через {@link HumanInput}) и с оглядкой на
 * пределы ({@link TikTokLimits}): разогрев, суточные потолки, редкие
 * комментарии. Автоматизация TikTok нарушает его правила — объёмы намеренно
 * маленькие.
 */
public final class TikTokTools {

    private static final Logger log = LoggerFactory.getLogger(TikTokTools.class);

    /** Комментарий длиннее — уже простыня, так не пишут. */
    private static final int MAX_COMMENT_LENGTH = 300;

    /** Сколько комментариев показывать за раз: больше — и контекст захлебнётся. */
    private static final int COMMENT_LIMIT = 20;

    private final Browser browser;
    private final HumanInput input;
    private final Vision vision;
    private final Config.TikTok config;
    private final TikTokLimits limits;
    private final RandomGenerator random;

    /**
     * Коллекция живой речи: комментарии, которые она прочитала, — тот же
     * материал для обучения манере, что и чужие сообщения в чатах. Подростки
     * пишут там ровно так же, только свободнее.
     */
    private ru.rainedev.raine.core.PhraseCollector phrases;

    /** Что оказалось на экране после действия. */
    private record Scene(String note, TikTok.VideoInfo video, byte[] shot, boolean loggedIn) {}

    private static final TikTok.VideoInfo NOTHING =
            new TikTok.VideoInfo("", "", "", "", List.of());

    public TikTokTools(Browser browser, HumanInput input, Vision vision,
                       Config.TikTok config, TikTokLimits limits, RandomGenerator random) {
        this.browser = browser;
        this.input = input;
        this.vision = vision;
        this.config = config;
        this.limits = limits;
        this.random = random;
    }

    /** Все инструменты TikTok — их добавляют в набор для свободного времени. */
    public List<Tool> tools() {
        return List.of(status(), openFeed(), next(), watchFeed(), watch(), like(), comment(), repost(),
                comments(), reply(), closeComments(), profile(),
                search(), openVideo(), downloadCurrent(), dmFriend());
    }

    public Tool status() {
        return Tool.named("tiktok_status")
                .describedAs("Check whether you're logged into TikTok and how much of today's limits "
                        + "you've already used.")
                .build(args -> {
                    boolean loggedIn;
                    try {
                        loggedIn = browser.onPage(TikTok::loggedIn);
                    } catch (RuntimeException e) {
                        return "The browser isn't available right now: " + rootMessage(e);
                    }
                    return (loggedIn
                            ? "You're logged into TikTok."
                            : "You're NOT logged into TikTok — you need to log in once on the headed "
                                    + "browser (through VNC or a real display) before you can interact.")
                            + "\n" + limits.usage();
                });
    }

    public Tool openFeed() {
        return Tool.named("tiktok_open_feed")
                .describedAs("Open your TikTok For-You feed to start scrolling.")
                .build(args -> {
                    limits.touchFirstUse();
                    Scene scene = capture(page -> {
                        TikTok.openFeed(page);
                        return "Opened your For-You feed.";
                    });
                    return render(scene, true);
                });
    }

    public Tool next() {
        return Tool.named("tiktok_next")
                .describedAs("Swipe to the next video in the feed and see what it is.")
                .build(args -> render(capture(page -> {
                    TikTok.next(page, input);
                    return "Moved to the next video.";
                }), true));
    }

    /**
     * Просмотр ленты по-настоящему человечески: смотрит несколько роликов
     * подряд, делает паузы, иногда листает чуть назад, двигает мышь.
     * Это не «действие» — это отдых/пользование приложением. Не возвращает
     * подробный разбор каждого ролика (для этого есть tiktok_watch), а
     * просто говорит, что она посидела в ленте.
     */
    public Tool watchFeed() {
        return Tool.named("tiktok_watch_feed")
                .describedAs("Just sit and watch your For-You feed for a while — like a person would. "
                        + "You'll watch 3–8 videos with realistic pauses, sometimes scrolling back a "
                        + "bit, moving the mouse. This doesn't analyze each video (use tiktok_watch for "
                        + "that), it just lets you spend time in the app naturally. Good for warming up "
                        + "the session and making your presence look real.")
                .optionalInteger("count", "How many videos to watch (default 5, max 10)")
                .build(args -> {
                    int count = Math.max(1, Math.min(args.path("count").asInt(5), 10));
                    limits.touchFirstUse();
                    // просто смотрит ленту: human-like паузы, случайные микро-действия
                    return capture(page -> {
                        input.watchVideoFeed(page, count);
                        return "Watched " + count + " videos in your feed — just chilling, "
                                + "moving like a real person does. Current video:";
                    }).note() + "\n" + render(capture(page -> "now on:"), true);
                });
    }

    public Tool watch() {
        return Tool.named("tiktok_watch")
                .describedAs("Really watch and understand the current video: reads the caption, author "
                        + "and hashtags, downloads a short clip, looks at its frames and listens to its "
                        + "audio. Takes time and costs — use it on videos you actually care about.")
                .build(args -> {
                    TikTok.VideoInfo info;
                    try {
                        info = browser.onPage(page -> {
                            settle(page);
                            return TikTok.readVideo(page);
                        });
                    } catch (RuntimeException e) {
                        return "The browser isn't available right now: " + rootMessage(e);
                    }
                    StringBuilder out = new StringBuilder(info.describe());
                    Path clip = TikTok.download(info, config.downloadDir()).orElse(null);
                    if (clip == null) {
                        return out.append("(You couldn't download the clip — you only read the caption "
                                + "and glanced at the thumbnail.)\n").toString();
                    }
                    if (vision == null) {
                        return out.append("(Vision is off — the clip is saved at ")
                                .append(clip.toAbsolutePath()).append(".)\n").toString();
                    }
                    List<VideoFrames.Frame> frames = VideoFrames.sample(clip);
                    String understood = vision.describeVideo(frames, List.of(), clip);
                    if (understood.isBlank()) {
                        out.append("(The clip didn't reveal much beyond the caption.)\n");
                    } else {
                        out.append(understood).append('\n');
                    }
                    return out.toString();
                });
    }

    public Tool like() {
        return Tool.named("tiktok_like")
                .describedAs("Like the current video — only when you actually like it, not everything "
                        + "you see.")
                .build(args -> {
                    Optional<String> blocked = limits.blockLike();
                    if (blocked.isPresent()) {
                        return blocked.get();
                    }
                    boolean[] done = {false};
                    Scene scene = capture(page -> {
                        done[0] = TikTok.like(page, input);
                        return done[0]
                                ? "Liked it."
                                : "Couldn't find the like button — try browser_snapshot and click it by ref.";
                    });
                    if (done[0]) {
                        limits.noteLike();
                    }
                    return render(scene, true);
                });
    }

    public Tool comment() {
        return Tool.named("tiktok_comment")
                .describedAs("Comment on the current video. Write like a real 19-year-old: short, "
                        + "natural, specific to THIS video. Never generic praise.")
                .requiredString("text", "The comment to post")
                .build(args -> {
                    String raw = args.path("text").asText("").strip();
                    if (raw.isBlank()) {
                        return "You didn't write anything to comment.";
                    }
                    String text = truncate(raw);
                    Optional<String> blocked = limits.blockComment();
                    if (blocked.isPresent()) {
                        return blocked.get();
                    }
                    boolean[] done = {false};
                    Scene scene = capture(page -> {
                        done[0] = TikTok.comment(page, input, text);
                        return done[0]
                                ? "Posted your comment."
                                : "Couldn't find the comment box — try browser_snapshot.";
                    });
                    if (done[0]) {
                        limits.noteComment();
                    }
                    return render(scene, true);
                });
    }

    public Tool repost() {
        return Tool.named("tiktok_repost")
                .describedAs("Repost the current video to your own feed.")
                .build(args -> {
                    Optional<String> blocked = limits.blockRepost();
                    if (blocked.isPresent()) {
                        return blocked.get();
                    }
                    boolean[] done = {false};
                    Scene scene = capture(page -> {
                        done[0] = TikTok.repost(page, input);
                        return done[0]
                                ? "Reposted it."
                                : "Couldn't find the repost button — try browser_snapshot.";
                    });
                    if (done[0]) {
                        limits.noteRepost();
                    }
                    return render(scene, true);
                });
    }

    /**
     * Чтение комментариев: без этого она могла бы только писать под роликом,
     * но не спорить и не соглашаться — то есть не участвовать в разговоре.
     */
    public Tool comments() {
        return Tool.named("tiktok_comments")
                .describedAs("Open the comments under the current video and read what people are actually "
                        + "saying — who wrote it, the text, how many likes, how deep in the thread. Pass "
                        + "more=true to scroll down and read the next batch instead of the top again. "
                        + "This is how you find out whether people see it the way you do, and whether "
                        + "anything there is worth answering.")
                .optionalBoolean("more", "Scroll down and read the next batch (default false)")
                .build(args -> {
                    boolean more = args.path("more").asBoolean(false);
                    AtomicReference<List<TikTok.Comment>> read = new AtomicReference<>(List.of());
                    Scene scene = capture(page -> {
                        if (more) {
                            TikTok.scrollComments(page, input, 700 + random.nextInt(700));
                            settle(page);
                        } else if (!TikTok.commentsOpen(page) && !TikTok.openComments(page, input)) {
                            return "Couldn't open the comments — try browser_snapshot and click the "
                                    + "comment button by its ref number.";
                        }
                        List<TikTok.Comment> found = TikTok.readComments(page, COMMENT_LIMIT);
                        read.set(found);
                        return commentList(found, more);
                    });
                    // прочитанное уходит в коллекцию живой речи: так она учится
                    // манере у настоящих подростков, а не у правил в промпте
                    learnFrom(read.get());
                    // скриншот не нужен: текст комментариев уже перед глазами,
                    // а зрение здесь только потратило бы время
                    return render(scene, false);
                });
    }

    /**
     * Ответ конкретному человеку — то, ради чего комментарии читают: не
     * «оставить комментарий», а возразить, поддержать, поспорить.
     */
    public Tool reply() {
        return Tool.named("tiktok_reply")
                .describedAs("Answer one specific comment you just read, by its number. Use it when "
                        + "someone said something you disagree with, or something you want to respond to. "
                        + "Write in your own voice — short, the way a person argues in a comment section, "
                        + "not like a moderator and not like a press release. You don't have to answer "
                        + "everything, and you don't have to answer at all; if you've said your piece, "
                        + "just go back to the video.")
                .requiredInteger("comment", "The comment number, from tiktok_comments")
                .requiredString("text", "What you want to say to them")
                .build(args -> {
                    int ref = args.path("comment").asInt(-1);
                    String raw = args.path("text").asText("").strip();
                    if (ref < 0) {
                        return "You didn't say which comment to answer — pass its number.";
                    }
                    if (raw.isBlank()) {
                        return "You didn't write anything to say.";
                    }
                    Optional<String> blocked = limits.blockComment();
                    if (blocked.isPresent()) {
                        return blocked.get();
                    }
                    String text = truncate(raw);
                    boolean[] done = {false};
                    AtomicReference<List<TikTok.Comment>> read = new AtomicReference<>(List.of());
                    Scene scene = capture(page -> {
                        done[0] = TikTok.replyTo(page, input, ref, text);
                        if (!done[0]) {
                            return "Couldn't find a Reply button on comment " + ref + " — read the "
                                    + "comments again (#tiktok_comments), or click it yourself through "
                                    + "browser_snapshot.";
                        }
                        settle(page);
                        // сразу видно, что получилось и что там теперь пишут —
                        // можно ответить ещё кому-то или уйти
                        List<TikTok.Comment> found = TikTok.readComments(page, COMMENT_LIMIT);
                        read.set(found);
                        return "You answered comment " + ref + ".\n" + commentList(found, false);
                    });
                    learnFrom(read.get());
                    if (done[0]) {
                        limits.noteComment();
                    }
                    return render(scene, false);
                });
    }

    /** Закрыть комментарии и вернуться к ролику. */
    public Tool closeComments() {
        return Tool.named("tiktok_comments_close")
                .describedAs("Close the comments and get back to watching the video.")
                .build(args -> render(capture(page -> {
                    // не рапортуем вслепую: если панель осталась открытой, она
                    // должна это знать и закрыть её сама через browser_snapshot
                    return TikTok.closeComments(page, input)
                            ? "Closed the comments — back to the video."
                            : "The comments are still open — close the button yourself "
                                    + "(browser_snapshot, then click its ref).";
                }), true));
    }

    /** Список комментариев для модели — с номерами, чтобы на них отвечать. */
    private String commentList(List<TikTok.Comment> found, boolean scrolled) {
        if (found.isEmpty()) {
            return scrolled
                    ? "Nothing new further down — looks like you've read them all."
                    : "The comments are open, but there's nothing to read.";
        }
        StringBuilder out = new StringBuilder();
        out.append(found.size()).append(" comments in view").append(scrolled ? " after scrolling" : "")
                .append(" (answer one with #tiktok_reply and its number, "
                        + "or read more with more=true):\n");
        for (TikTok.Comment comment : found) {
            out.append(comment.render()).append('\n');
        }
        return out.toString();
    }

    private static String truncate(String text) {
        return text.length() > MAX_COMMENT_LENGTH ? text.substring(0, MAX_COMMENT_LENGTH) : text;
    }

    /**
     * Профиль автора: имя, bio, подписчики и — главное — Telegram, который
     * автор сам оставил в описании. Ник в TikTok и ник в Telegram почти всегда
     * разные, поэтому угадывать по первому бесполезно: нужно прочитать bio.
     */
    public Tool profile() {
        return Tool.named("tiktok_profile")
                .describedAs("Open a TikTok user's profile and read it: name, bio, followers. This is where "
                        + "authors usually leave their Telegram ('тг: @channel', 't.me/channel'). Leave the "
                        + "handle empty to read the author of the video you're on. Their TikTok and Telegram "
                        + "handles are often different — read the bio instead of guessing the nickname.")
                .optionalString("handle", "The @username to open; empty = the current video's author")
                .build(args -> {
                    String handle = args.path("handle").asText("").strip();
                    if (handle.isEmpty()) {
                        try {
                            TikTok.VideoInfo info = browser.onPage(page -> {
                                settle(page);
                                return TikTok.readVideo(page);
                            });
                            handle = info.author() == null ? "" : info.author().strip();
                        } catch (RuntimeException e) {
                            return "The browser isn't available right now: " + rootMessage(e);
                        }
                    }
                    if (handle.isEmpty()) {
                        return "I don't know whose profile to open — you're not on a video right now. "
                                + "Pass a handle, like @someone.";
                    }
                    final String who = handle;
                    try {
                        String[] described = new String[1];
                        byte[] screen = browser.onPage(page -> {
                            TikTok.ProfileInfo info = TikTok.readProfile(page, who);
                            settle(page);
                            described[0] = info.describe();
                            return shot(page);
                        });
                        String seen = describe(screen);
                        return described[0] + (seen.isBlank() ? "" : seen + "\n");
                    } catch (RuntimeException e) {
                        return "The browser isn't available right now: " + rootMessage(e);
                    }
                });
    }

    public Tool search() {
        return Tool.named("tiktok_search")
                .describedAs("Search TikTok for something you're personally into — a topic, a sound, "
                        + "a hashtag or a person. Not just the For-You feed.")
                .requiredString("query", "What to search for")
                .build(args -> {
                    String query = args.path("query").asText("").strip();
                    if (query.isBlank()) {
                        return "You didn't say what to search for.";
                    }
                    Scene scene = capture(page -> {
                        TikTok.search(page, query);
                        return "Searched TikTok for: " + query;
                    });
                    return render(scene, true);
                });
    }

    public Tool openVideo() {
        return Tool.named("tiktok_open_video")
                .describedAs("Open a specific TikTok video by its URL.")
                .requiredString("url", "The TikTok video URL")
                .build(args -> {
                    String url = args.path("url").asText("").strip();
                    if (url.isBlank()) {
                        return "You didn't give a URL.";
                    }
                    // первый открытый ролик запускает отсчёт разогрева — не важно,
                    // из ленты он или его кинули в личку
                    limits.touchFirstUse();
                    Scene scene = capture(page -> {
                        TikTok.openVideo(page, url);
                        return "Opened " + url;
                    });
                    return render(scene, true);
                });
    }

    public Tool downloadCurrent() {
        return Tool.named("tiktok_download_current")
                .describedAs("Download the current video to your TikTok folder, so you can post it to "
                        + "your Telegram story, send it to a friend in Telegram, or attach it to a DM.")
                .build(args -> {
                    TikTok.VideoInfo info;
                    try {
                        info = browser.onPage(page -> {
                            settle(page);
                            return TikTok.readVideo(page);
                        });
                    } catch (RuntimeException e) {
                        return "The browser isn't available right now: " + rootMessage(e);
                    }
                    Optional<Path> clip = TikTok.download(info, config.downloadDir());
                    return clip.map(saved -> "Saved the video to " + saved.toAbsolutePath()
                                    + " — you can now send this file to Telegram or attach it to a TikTok DM.")
                            .orElse("Couldn't download this video (no yt-dlp, and the player source "
                                    + "wasn't reachable directly).");
                });
    }

    public Tool dmFriend() {
        return Tool.named("tiktok_dm_friend")
                .describedAs("Send a TikTok DM to a friend. Write like you actually know them. You can "
                        + "attach a video you downloaded.")
                .requiredString("handle", "Their TikTok @username")
                .requiredString("text", "The message to send")
                .optionalString("video", "Absolute path to a downloaded video to attach (optional)")
                .build(args -> {
                    String handle = args.path("handle").asText("").strip();
                    String text = args.path("text").asText("").strip();
                    Path video = resolveVideo(args.path("video").asText("").strip());
                    if (handle.isBlank()) {
                        return "You didn't say who to message.";
                    }
                    if (text.isBlank() && video == null) {
                        return "Nothing to send — write a message or attach a video.";
                    }
                    Optional<String> blocked = limits.blockDm();
                    if (blocked.isPresent()) {
                        return blocked.get();
                    }
                    boolean[] done = {false};
                    Scene scene = capture(page -> {
                        done[0] = TikTok.dm(page, input, handle, text, video);
                        return done[0]
                                ? "Sent a DM to @" + handle + (video != null ? " with the video." : ".")
                                : "Couldn't open a DM with @" + handle
                                        + " — they may not accept messages, or the layout changed.";
                    });
                    if (done[0]) {
                        limits.noteDm();
                    }
                    return render(scene, false);
                });
    }

    /** Куда складывать живые фразы из прочитанных комментариев. */
    public void phrases(ru.rainedev.raine.core.PhraseCollector phrases) {
        this.phrases = phrases;
    }

    /**
     * Скармливает коллекции прочитанные комментарии. Свои пропускает: учиться
     * у самой себя — значит закреплять собственную манеру вместо живой.
     */
    void learnFrom(List<TikTok.Comment> comments) {
        if (phrases == null || comments.isEmpty()) {
            return;
        }
        String mine = config.username() == null ? "" : config.username().strip()
                .replaceFirst("^@", "").toLowerCase();
        for (TikTok.Comment comment : comments) {
            String author = comment.author() == null ? "" : comment.author().strip()
                    .replaceFirst("^@", "").toLowerCase();
            if (!mine.isBlank() && mine.equals(author)) {
                continue;
            }
            phrases.observeText(comment.text());
        }
    }

    /**
     * Делает действие на потоке браузера и снимает, что теперь на экране.
     * Техническая ошибка не роняет ход — она попадает в ответ.
     */
    private Scene capture(Function<Page, String> action) {
        try {
            return browser.onPage(page -> {
                String note;
                try {
                    note = action.apply(page);
                } catch (RuntimeException e) {
                    log.debug("Действие в TikTok не удалось: {}", e.getMessage());
                    note = "That didn't work: " + rootMessage(e);
                }
                settle(page);
                return new Scene(note == null ? "" : note, TikTok.readVideo(page),
                        shot(page), TikTok.loggedIn(page));
            });
        } catch (RuntimeException e) {
            log.warn("Браузер недоступен для TikTok: {}", e.getMessage());
            return new Scene("The browser isn't available right now: " + rootMessage(e), NOTHING, null, false);
        }
    }

    private String render(Scene scene, boolean withScreen) {
        StringBuilder out = new StringBuilder();
        if (!scene.note().isBlank()) {
            out.append(scene.note()).append('\n');
        }
        boolean hasVideo = scene.video() != null && !scene.video().url().isBlank();
        if (hasVideo) {
            if (!scene.loggedIn()) {
                out.append("(Heads-up: you don't seem to be logged into TikTok — some actions won't "
                        + "work until you log in on the headed browser.)\n");
            }
            out.append(scene.video().describe());
        }
        if (withScreen) {
            String seen = describe(scene.shot());
            if (!seen.isBlank()) {
                out.append(seen).append('\n');
            }
        }
        return out.toString();
    }

    private String describe(byte[] shot) {
        if (vision == null || shot == null || shot.length == 0) {
            return "";
        }
        try {
            return vision.describe(shot, Vision.Kind.PHOTO, List.of());
        } catch (RuntimeException e) {
            log.debug("Не удалось разглядеть экран TikTok: {}", e.getMessage());
            return "";
        }
    }

    private void settle(Page page) {
        try {
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
        } catch (RuntimeException ignored) {
            // SPA может и не дойти до этого состояния — не беда
        }
        page.waitForTimeout(700 + random.nextInt(900));
    }

    private byte[] shot(Page page) {
        try {
            return page.screenshot();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Path resolveVideo(String argument) {
        if (argument == null || argument.isBlank()) {
            return null;
        }
        Path path = Path.of(argument);
        if (!path.isAbsolute()) {
            path = config.downloadDir().resolve(argument);
        }
        return Files.exists(path) ? path : null;
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
