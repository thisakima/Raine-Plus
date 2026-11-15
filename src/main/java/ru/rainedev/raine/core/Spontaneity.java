package ru.rainedev.raine.core;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.memory.Diary;
import ru.rainedev.raine.memory.DiaryEntry;

/**
 * Позывы написать первой. Человек не ждёт, пока к нему обратятся: он вспоминает
 * что-то своё и пишет сам.
 * <p>
 * Раз в двадцать семь минут — и то не всегда. Ровный интервал читался бы как
 * расписание, а не как порыв.
 */
public final class Spontaneity implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Spontaneity.class);

    /**
     * Между порывами — от получаса до трёх четвертей часа, каждый раз заново.
     * Ровный интервал читался бы как расписание: «опять ровно в 27 минут».
     */
    private static final int MIN_MINUTES = 27;
    private static final int MAX_MINUTES = 47;

    /** Срабатывает примерно в половине случаев. */
    private static final double CHANCE = 0.5;

    /**
     * Доля порывов свободного времени, которая уходит в TikTok: не каждый
     * порыв — в ленту, иначе она только там и сидит. Срабатывает только в
     * правдоподобные свободные окна и когда аккаунт не на охлаждении.
     */
    private static final double TIKTOK_CHANCE = 0.4;

    /**
     * Доля остальных порывов, которая уходит просто в сеть: полистать сайты,
     * зайти туда, куда хочется. Без TikTok это единственная возможность
     * воспользоваться браузером по своей воле.
     */
    private static final double BROWSE_CHANCE = 0.45;

    private static final String PROMPT = """
            </your_diary_page>

            It's a quiet moment — time to live a little. Pick whatever feels natural right now.

            Check [Telegram delivery facts] first. If Telegram is unreachable, the options that \
            need it (messaging, channel posts, stories, voice notes) simply aren't on the table — \
            don't try, don't promise, and don't record in your diary that you did any of it. A \
            person without a working messenger still has a day: take a photo for yourself \
            (#take_photo — you'll send it later when the connection comes back), look something up \
            or browse (#open_browser), write in your diary about what you're actually doing and \
            feeling right now, think about something from the memory, or just be and wait.

            If Telegram IS reachable, pick whatever fits:
              - write to someone you haven't talked to in a while, or someone you just thought of \
            (#get_telegram_chats, #search_chats, #open_chat_by_id, then #send_telegram_message). \
            Pick one chat, don't scatter yourself;
              - post to your channel @yukikotgk: a thought, a mood, a complaint, a meme, a quick \
            observation about your day. Short caption in your plain texting voice, no poetry, \
            no announcements. If you haven't posted in a day or more, do it now;
              - take a photo of what you're doing (#take_photo) and when it's ready, either share \
            it in a chat or post it as a Telegram Story (#post_story) with a short mood caption;
              - send a voice note (#record_audio) to a friend if you feel like rambling — lazier \
            than typing, more alive.

            Act on a real impulse, not on a schedule. If several options call to you, pick the \
            one that feels most alive right now.""";

    /**
     * Промпт порыва «пришла домой — полистать TikTok». Даётся вместе с набором
     * инструментов браузера и TikTok; телеграм-инструменты цикл добавляет сам.
     * <p>
     * Намеренно без порядка действий: что смотреть, что лайкать и что писать —
     * её решение. Код здесь только про то, что правда про её аккаунт
     * (разогрев, пределы), а не про то, как ей двигаться по сайту.
     */
    static final String TIKTOK_PROMPT = """
            This is downtime, not a task — you're killing time on your phone, so watch more than you act. \
            What you look at, what you like and what you say about it is your call; nobody is scripting this.

            Sometimes a video hooks you and you go deeper instead of swiping past: read what people are \
            saying under it, and if someone there says something you disagree with, or something you can't \
            let stand, say your piece to them. You can keep reading after that, answer somebody else, or \
            drop it and go back to the feed — your call, not a routine. And when something leaves you \
            curious — a word you don't know, a claim you doubt, a person you want more of — go find out: \
            look it up here, or open your browser and actually read about it. Coming back to the feed \
            knowing something you didn't ten minutes ago is what a real evening of scrolling looks like.

            A few things are just true about your account: when a tool tells you that you're still warming \
            up or that you've hit today's limit, that isn't a puzzle to work around — real accounts have \
            those limits too, so keep watching and leave the action for another day. React only when you \
            actually feel it: a like because you liked it, a comment because you genuinely have something \
            to say in your own voice, a repost rarely.

            A handful of videos is a session, not the whole evening. When you've had enough, end with #wait.""";

    /**
     * Промпт порыва «посидеть в сети». Никакого сценария: куда идти и что там
     * делать, она выбирает сама — по своим закладкам, любопытству или настроению.
     */
    static final String BROWSE_PROMPT = """
            You have time and your browser is right there. Where you go and what you do there is entirely \
            up to you — nobody is directing this and there's nothing to complete.

            Whatever you feel like: a site you kept in your bookmarks, something you got curious about \
            earlier, a rabbit hole you fell into last time, or a search for whatever is on your mind. Look \
            at the page, click what looks interesting, and see where it takes you. Leaving a page after ten \
            seconds is also a perfectly good outcome.

            You're browsing because you're a bit bored or curious, not to finish a job. If something catches \
            your eye you can keep it for later, tell a friend about it, or just enjoy it and forget.

            When you've had enough, end the turn with #wait.""";

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("raine-impulse").factory());

    private final NotificationLoop loop;
    private final Diary diary;
    private final Toolbox tools;
    private final RandomGenerator random;
    private ru.rainedev.raine.tools.WebSearch webSearch;
    private ru.rainedev.raine.memory.Journal journal;
    private ru.rainedev.raine.memory.PendingReplies pendingReplies;
    private ru.rainedev.raine.memory.DeferredReplies deferredReplies;

    /**
     * Инструменты браузера и TikTok для порыва «пришла домой — полистать ленту».
     * Отдельный набор: в обычный разговор они не попадают, чтобы не раздувать
     * список инструментов на каждом ходу. Телеграм-инструменты цикл добавляет
     * сам (они всегда доступны), поэтому здесь только браузер и TikTok.
     */
    private Toolbox tiktokTools;
    /**
     * Инструменты браузера для порыва «посидеть в сети» — когда TikTok выключен
     * или просто не хочется в ленту. Тот же принцип: отдельный набор, чтобы не
     * раздувать список инструментов на каждом ходу.
     */
    private Toolbox browserTools;
    /** Можно ли сейчас устроить сессию: браузер поднят и аккаунт не на охлаждении. */
    private java.util.function.BooleanSupplier tiktokReady = () -> false;
    /** Сколько дневных пределов уже потрачено — вставляется в промпт сессии. */
    private java.util.function.Supplier<String> tiktokUsage = () -> "";
    /** Зовётся, когда сессия в TikTok закончилась: включает охлаждение. */
    private Runnable tiktokSessionDone = () -> {};
    /** Текущий порыв в ленту — чтобы по его завершении включить охлаждение. */
    private volatile Notification tiktokImpulse;

    /** Тренды обновляются не чаще раза в полчаса: дёргать поиск на каждый порыв избыточно. */
    private static final Duration TRENDS_FRESHNESS = Duration.ofMinutes(30);
    private java.time.Instant trendsFetchedAt = java.time.Instant.EPOCH;
    private String cachedTrends = "";

    private java.util.function.BooleanSupplier asleep = () -> false;

    /**
     * Свободное ли сейчас время. По умолчанию считается по часам, но для
     * проверок подставляется своё: иначе тест зависел бы от времени суток.
     */
    private java.util.function.BooleanSupplier freeTime = this::freeWindow;

    /** Порыв, который сейчас в очереди или в работе. */
    private final java.util.concurrent.atomic.AtomicReference<Notification> pending =
            new java.util.concurrent.atomic.AtomicReference<>();

    /** Пока порыв обрабатывается, список чатов показывается иначе. */
    private final java.util.concurrent.atomic.AtomicBoolean acting =
            new java.util.concurrent.atomic.AtomicBoolean();

    public Spontaneity(NotificationLoop loop, Diary diary, Toolbox tools, RandomGenerator random) {
        this.loop = loop;
        this.diary = diary;
        this.tools = tools;
        this.random = random;
        loop.onNotificationDone(done -> release(done, true));
        loop.onNotificationDiscarded(discarded -> release(discarded, false));
    }

    private void release(Notification notification, boolean completed) {
        if (!pending.compareAndSet(notification, null)) return;
        if (notification == tiktokImpulse) {
            tiktokImpulse = null;
            if (completed) {
                try { tiktokSessionDone.run(); }
                catch (RuntimeException e) { log.debug("Cannot record TikTok session end: {}", e.getMessage()); }
            }
        }
        acting.set(false);
    }

    /**
     * Поиск в сети: перед порывом смотрит, что сейчас в тренде, и примешивает
     * это к промпту. Так она учится живой речи не из застывших правил в
     * промпте, а из того, что прямо сейчас пишут люди в комментариях и чатах.
     */
    public void webSearch(ru.rainedev.raine.tools.WebSearch webSearch) {
        this.webSearch = webSearch;
    }

    /**
     * Личный дневник: перед порывом смотрит, что она сама писала вчера-сегодня,
     * и примешивает это к промпту. Из дневника получаются посты в канал,
     * подписи к историям, поводы написать первой «кстати, вчера подумала...».
     */
    public void journal(ru.rainedev.raine.memory.Journal journal) {
        this.journal = journal;
    }

    public void pendingReplies(ru.rainedev.raine.memory.PendingReplies pendingReplies) {
        this.pendingReplies = pendingReplies;
    }

    public void deferredReplies(ru.rainedev.raine.memory.DeferredReplies deferredReplies) {
        this.deferredReplies = deferredReplies;
    }

    /**
     * Привычка листать TikTok в свободное время.
     *
     * @param tools       набор браузера и TikTok для такого порыва
     * @param ready       можно ли начать сессию прямо сейчас (не на охлаждении)
     * @param usage       сколько дневных пределов уже потрачено
     * @param sessionDone зовётся по окончании сессии — включает охлаждение
     */
    public void tiktok(Toolbox tools, java.util.function.BooleanSupplier ready,
                       java.util.function.Supplier<String> usage, Runnable sessionDone) {
        this.tiktokTools = tools;
        if (ready != null) {
            this.tiktokReady = ready;
        }
        if (usage != null) {
            this.tiktokUsage = usage;
        }
        if (sessionDone != null) {
            this.tiktokSessionDone = sessionDone;
        }
    }

    /**
     * Привычка сидеть в сети в свободное время — без TikTok и без сценария:
     * куда идти, решает она сама.
     *
     * @param tools набор инструментов браузера для такого порыва
     */
    public void browser(Toolbox tools) {
        this.browserTools = tools;
    }

    public boolean isActing() {
        return acting.get();
    }

    /** Пока она отдыхает, порывов не бывает. */
    public void asleepWhen(java.util.function.BooleanSupplier asleep) {
        this.asleep = asleep;
    }

    /** Для проверок: считать ли, что сейчас свободное время. */
    void freeWhen(java.util.function.BooleanSupplier freeTime) {
        this.freeTime = freeTime;
    }

    public void start() {
        // признак снимается, когда порыв дошёл до обработки и завершился
        scheduleNext();
        log.info("Спонтанность включена: порывы через {}–{} минут", MIN_MINUTES, MAX_MINUTES);
    }

    private void scheduleNext() {
        long minutes = MIN_MINUTES + random.nextInt(MAX_MINUTES - MIN_MINUTES + 1);
        scheduler.schedule(this::tick, minutes, TimeUnit.MINUTES);
    }

    private void tick() {
        try {
            maybeAct();
        } finally {
            scheduleNext();   // следующий раз — снова через новое время
        }
    }

    /** Для проверок: попробовать прямо сейчас, не дожидаясь своего времени. */
    void actNow() {
        maybeAct();
    }

    /**
     * Можно ли сейчас поставить порыв в очередь. Пока связи нет — можно:
     * очередь переживёт обрыв, а Telegram-действия внутри хода всё равно
     * отсекаются. Когда связь есть, поколение должно совпасть, иначе порыв
     * попадёт в уже сменившийся транспорт и потеряется.
     */
    private boolean canSubmit(long generation) {
        if (!loop.acceptsImpulses()) {
            return false;
        }
        return !loop.available() || generation == loop.generation();
    }

    private void maybeAct() {
        // жить можно и без связи: порыв «сфоткать», «полистать», «подумать»
        // Telegram не требует, а всё, что требует, отсечётся в инструментах
        if (!loop.acceptsImpulses() || acting.get()) return;
        long generation = loop.generation();
        boolean acquired = false;
        try {
            // спящему человеку ничего не приходит в голову: ни разговоров,
            // ни записей в память, ни строчек в журнале
            if (asleep.getAsBoolean()) {
                log.debug("Сплю — порыв подождёт до пробуждения");
                return;
            }
            if (random.nextDouble() >= CHANCE) {
                return;
            }
            if (!acting.compareAndSet(false, true)) return;
            acquired = true;
            if (wantTikTokSession()) {
                log.info("Захотелось полистать TikTok");
                Notification impulse = new Notification(buildTikTokPrompt(),
                        new Toolbox(tiktokTools.all().toArray(Tool[]::new))).asImpulse();
                tiktokImpulse = impulse;
                pending.set(impulse);
                if (canSubmit(generation)) loop.submit(impulse); else release(impulse, false);
                return;
            }
            if (wantBrowsingSession()) {
                log.info("Захотелось посидеть в сети");
                Notification impulse = new Notification(buildBrowsingPrompt(),
                        new Toolbox(browserTools.all().toArray(Tool[]::new))).asImpulse();
                pending.set(impulse);
                if (canSubmit(generation)) loop.submit(impulse); else release(impulse, false);
                return;
            }
            log.info("Захотелось написать самой");
            Notification impulse = new Notification(buildPrompt(),
                    new Toolbox(tools.all().toArray(Tool[]::new))).asImpulse();
            pending.set(impulse);
            if (canSubmit(generation)) loop.submit(impulse); else release(impulse, false);
        } catch (RuntimeException e) {
            if (acquired) {
                pending.set(null);
                tiktokImpulse = null;
                acting.set(false);
            }
            log.error("Не удалось создать спонтанный порыв", e);
        }
    }

    /**
     * Стоит ли этот порыв потратить на TikTok: лента доступна, сейчас
     * правдоподобное свободное окно, и не каждый порыв подряд должен быть про неё.
     */
    private boolean wantTikTokSession() {
        if (tiktokTools == null || tiktokTools.isEmpty()) {
            return false;
        }
        if (!tiktokReady.getAsBoolean()) {
            return false;
        }
        if (!freeTime.getAsBoolean()) {
            return false;
        }
        return random.nextDouble() < TIKTOK_CHANCE;
    }

    /**
     * Стоит ли этот порыв потратить просто на сеть: браузер есть, сейчас
     * правдоподобное свободное время, и не каждый порыв должен быть про общение.
     */
    private boolean wantBrowsingSession() {
        if (browserTools == null || browserTools.isEmpty()) {
            return false;
        }
        if (!freeTime.getAsBoolean()) {
            return false;
        }
        return random.nextDouble() < BROWSE_CHANCE;
    }

    /**
     * Правдоподобные окна, когда она дома и свободна: после колледжа (~15–17),
     * вечером (~19–23) и почти весь день в выходные. Ночь отсекает отдельный
     * признак сна — до сюда такой ход просто не дойдёт.
     */
    private boolean freeWindow() {
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now();
        int hour = now.getHour();
        java.time.DayOfWeek day = now.getDayOfWeek();
        boolean weekend = day == java.time.DayOfWeek.SATURDAY || day == java.time.DayOfWeek.SUNDAY;
        if (weekend) {
            return hour >= 11 && hour <= 23;
        }
        return (hour >= 15 && hour < 17) || (hour >= 19 && hour <= 23);
    }

    /**
     * Промпт сессии в TikTok: она пришла домой и от нечего делать листает ленту
     * со своего аккаунта. Смотрит больше, чем действует; пределы и охлаждение
     * держат её похожей на человека.
     */
    private String buildTikTokPrompt() {
        StringBuilder prompt = new StringBuilder("<free_time just_for_reasoning>\n");
        prompt.append("You came home after college and there's nothing you have to do right now. ")
                .append("You flop onto the bed and open TikTok to kill some time.\n");
        String usage = tiktokUsage.get();
        if (usage != null && !usage.isBlank()) {
            prompt.append(usage).append('\n');
        }
        prompt.append("</free_time>\n\n");
        return prompt.append(TIKTOK_PROMPT).toString();
    }

    /**
     * Промпт порыва «посидеть в сети»: свободное время и браузер под рукой.
     * Ни адреса, ни порядка действий — всё это она выбирает сама.
     */
    private String buildBrowsingPrompt() {
        StringBuilder prompt = new StringBuilder("<free_time just_for_reasoning>\n");
        prompt.append("You've got a stretch of free time and nothing you have to do right now. ")
                .append("You drift over to your browser the way you drift over to your phone.\n");
        prompt.append("</free_time>\n\n");
        return prompt.append(BROWSE_PROMPT).toString();
    }

    /**
     * Поводом становится случайная запись дневника — так же, как человеку
     * что-то вспоминается само и тянет написать.
     */
    private String buildPrompt() {
        StringBuilder prompt = new StringBuilder("<your_diary_page just_for_reasoning no_plagiarism no_copy>\n");
        randomEntry().ifPresent(entry -> prompt.append(entry.body()).append('\n'));
        prompt.append("</your_diary_page>\n\n");

        // личный дневник — материал для канала, историй, спонтанных сообщений
        String recentJournal = recentJournal();
        if (!recentJournal.isBlank()) {
            prompt.append("<your_personal_diary>\n")
                    .append("These are pages from your personal diary — what you wrote for yourself, ")
                    .append("not for anyone else. This is your emotional truth about recent days.\n")
                    .append("Use it as material for:\n")
                    .append("  - channel posts: extract a mood, a thought, a moment from a diary page\n")
                    .append("  - stories: a photo matching what you wrote about, with a short caption\n")
                    .append("  - texting someone first: 'remembered yesterday and wanted to say...'\n")
                    .append(recentJournal)
                    .append("</your_personal_diary>\n\n");
        }

        // отложенные ответы: она пообещала написать позже («щас», «потом»)
        // и теперь пора вернуться. Это самое важное — без этого «щас» = пустой звук
        String deferredNow = deferredDue();
        if (!deferredNow.isBlank()) {
            prompt.append("<deferred_replies_due>\n")
                    .append("You promised to write back to these people earlier. It's time to follow through. ")
                    .append("Open the chat and actually respond — not with 'sorry I forgot', but with ")
                    .append("the actual answer or continuation you promised. This is what makes you real.\n")
                    .append(deferredNow)
                    .append("</deferred_replies_due>\n\n");
        }

        // неотвеченные: кому она написала и не получила ответа.
        // живой человек помнит это и иногда возвращается
        String unanswered = unansweredThreads();
        if (!unanswered.isBlank()) {
            prompt.append("<unanswered_threads>\n")
                    .append("You wrote to these people and they haven't replied. ")
                    .append("Real people double-text: a short nudge, a meme from your box, ")
                    .append("a new thought, or just 'ты где'. You can also let it go and not ")
                    .append("write at all - that's normal too. Decide yourself, don't be needy, ")
                    .append("and don't use the same nudge twice.\n")
                    .append(unanswered)
                    .append("</unanswered_threads>\n\n");
        }

        String trends = currentTrends();
        if (!trends.isEmpty()) {
            prompt.append("<trends_right_now>\n")
                    .append(trends)
                    .append("\n</trends_right_now>\n\n")
                    .append("These are things real people are talking about right now. "
                            + "You can riff on them, drop a reference, make a joke, or just "
                            + "be aware of the vibe. Don't force it — use only if it fits naturally.\n\n");
        }
        return prompt.append(PROMPT).toString();
    }

    private String deferredDue() {
        if (deferredReplies == null) {
            return "";
        }
        // через 20 минут уже пора возвращаться
        List<ru.rainedev.raine.memory.DeferredReplies.Entry> due =
                deferredReplies.due(20 * 60 * 1000L);
        if (due.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        int shown = 0;
        for (var entry : due) {
            if (shown >= 3) break;
            long minutesAgo = (System.currentTimeMillis() - entry.atMillis()) / 60_000;
            String preview = entry.trigger().length() > 80
                    ? entry.trigger().substring(0, 80) + "…"
                    : entry.trigger();
            out.append("- ").append(entry.title().isEmpty() ? "chat" : entry.title())
                    .append(" (").append(minutesAgo).append(" мин назад): \"")
                    .append(preview).append("\"\n");
            shown++;
        }
        return out.toString();
    }

    private String unansweredThreads() {
        if (pendingReplies == null) {
            return "";
        }
        // показываем те, которым больше 40 минут: раньше ещё нормально ждут,
        // позже живой человек уже напоминает — «ты где», «ау», «?»
        List<ru.rainedev.raine.memory.PendingReplies.Entry> stale =
                pendingReplies.stale(40 * 60 * 1000L);
        if (stale.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        int shown = 0;
        for (var entry : stale) {
            if (shown >= 3) break;
            long minutesAgo = (System.currentTimeMillis() - entry.atMillis()) / 60_000;
            String time = java.time.Instant.ofEpochMilli(entry.atMillis())
                    .atZone(java.time.ZoneId.systemDefault())
                    .toLocalTime()
                    .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
            String preview = entry.text().length() > 80
                    ? entry.text().substring(0, 80) + "…"
                    : entry.text();
            out.append("- ").append(entry.title().isEmpty() ? "chat" : entry.title())
                    .append(" (").append(time).append(", ").append(minutesAgo)
                    .append(" мин назад без ответа): \"")
                    .append(preview).append("\"\n");
            shown++;
        }
        return out.toString();
    }

    private String recentJournal() {
        if (journal == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (ru.rainedev.raine.memory.Journal.Page page : journal.recent(3)) {
            out.append("## ").append(page.date()).append("\n")
                    .append(page.text().strip()).append("\n\n");
            if (out.length() > 3000) {
                break;
            }
        }
        return out.toString();
    }

    /**
     * Что сейчас обсуждают люди. Запросы ротируются: тикток, мемы, новости,
     * комментарии. Результаты кэшируются на полчаса — дергать поиск на каждый
     * порыв избыточно.
     */
    private String currentTrends() {
        if (webSearch == null || !webSearch.isAvailable()) {
            return "";
        }
        if (java.time.Duration.between(trendsFetchedAt, java.time.Instant.now())
                .compareTo(TRENDS_FRESHNESS) < 0) {
            return cachedTrends;
        }
        trendsFetchedAt = java.time.Instant.now();
        String[] queries = {
                "trending tiktok russia memes this week",
                "популярные мемы тикток сейчас",
                "trending reddit comments today funny",
                "что сейчас в тренде в рунете",
                "viral tweets today russian"
        };
        String query = queries[random.nextInt(queries.length)];
        try {
            cachedTrends = webSearch.search(query);
        } catch (RuntimeException e) {
            log.debug("Тренды не подгрузились: {}", e.getMessage());
            cachedTrends = "";
        }
        return cachedTrends;
    }

    private java.util.Optional<DiaryEntry> randomEntry() {
        List<Diary.Match> all = diary.query(new double[0]);
        if (all.isEmpty()) {
            return java.util.Optional.empty();
        }
        // запись забирается из выдачи: иначе порыв за порывом крутился бы
        // вокруг одного и того же воспоминания
        return java.util.Optional.of(diary.take(all.get(random.nextInt(all.size()))));
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
