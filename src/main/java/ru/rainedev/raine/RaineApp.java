package ru.rainedev.raine;

import it.tdlight.client.SimpleAuthenticationSupplier;
import it.tdlight.client.SimpleTelegramClient;
import it.tdlight.client.SimpleTelegramClientBuilder;
import it.tdlight.jni.TdApi;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.config.Config;
import ru.rainedev.raine.core.Notification;
import ru.rainedev.raine.core.NotificationLoop;
import ru.rainedev.raine.core.Rest;
import ru.rainedev.raine.core.Spontaneity;
import ru.rainedev.raine.core.Toolbox;
import ru.rainedev.raine.llm.LlmClient;
import ru.rainedev.raine.llm.OpenAiCompatibleClient;
import ru.rainedev.raine.memory.Diary;
import ru.rainedev.raine.memory.DiaryMemory;
import ru.rainedev.raine.memory.DiaryWriter;
import ru.rainedev.raine.memory.Recall;
import ru.rainedev.raine.memory.WorkingMemory;
import ru.rainedev.raine.phone.ChatKind;
import ru.rainedev.raine.phone.ChatScreen;
import ru.rainedev.raine.phone.MediaDescriber;
import ru.rainedev.raine.phone.MessageFormatter;
import ru.rainedev.raine.phone.Notifications;
import ru.rainedev.raine.prompt.Prompts;
import ru.rainedev.raine.telegram.Lockdown;
import ru.rainedev.raine.telegram.Telegram;
import ru.rainedev.raine.telegram.TelegramActions;
import ru.rainedev.raine.tools.AntiRepeat;
import ru.rainedev.raine.tools.TelegramTools;
import ru.rainedev.raine.tools.WebSearch;
import ru.rainedev.raine.vision.TelegramMedia;
import ru.rainedev.raine.vision.Vision;

/** Связывает Telegram, промпты и цикл обработки в одно живое целое. */
public final class RaineApp implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RaineApp.class);

    private final Config config;
    private final SimpleTelegramClient client;
    private final ru.rainedev.raine.telegram.TelegramDelivery delivery;
    private final java.util.Map<Long, TdApi.Message> offlineIncoming = new java.util.LinkedHashMap<>();

    /** Правки в config.properties подхватываются на ходу — как и промпты. */
    private ru.rainedev.raine.config.LiveConfig settings;

    /** Перенести разговор в память. Зовётся при переполнении и при остановке. */
    private java.util.function.Consumer<String> rememberConversation = why -> { };

    /** Разговоры, которые не удалось пересказать: сеть отвалилась не вовремя. */
    private ru.rainedev.raine.memory.PendingConversations pending;

    private final java.util.concurrent.atomic.AtomicBoolean closing =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * TDLib начинает закрываться сам, не спрашивая нас: при остановке процесса
     * его собственный обработчик может сработать раньше нашего. Обращаться
     * к закрывающемуся клиенту нельзя — вызов повисает на минуты.
     */
    private volatile boolean telegramClosing;

    private Telegram telegram;
    private TelegramTools tools;
    private NotificationLoop loop;
    /** Локальная веб-панель наблюдения: только чтение, только localhost. */
    private ru.rainedev.raine.ui.WebUi webUi;
    private Thread brain;
    private Rest rest;
    private TelegramMedia telegramMedia;
    private Vision vision;
    private Lockdown lockdown;
    private TelegramActions actions;
    private ru.rainedev.raine.core.Surroundings around;
    /** Тело браузера: живой Chromium для сайтов и соцсетей. Закрывается при остановке. */
    private ru.rainedev.raine.browser.Browser browser;
    /**
     * Инструменты TikTok (браузер + TikTok). Тот же набор, что для свободного
     * листания; точечно даём его на ход, когда в личку кидают ссылку на ролик,
     * чтобы она открыла его и отреагировала в самом TikTok. null, если TikTok выключен.
     */
    private Toolbox tiktokBox;
    /** Ссылки TikTok, включая короткие vm./vt. — их кидают чаще всего. */
    private static final java.util.regex.Pattern TIKTOK_LINK =
            java.util.regex.Pattern.compile("https?://[a-zA-Z0-9._-]*tiktok[.]com/[a-zA-Z0-9/_@.?=&%-]+",
                    java.util.regex.Pattern.CASE_INSENSITIVE);
    private final java.util.Random random = new java.util.Random();

    /** Столько чатов просматривается при запуске в поисках непрочитанного. */
    private static final int CATCH_UP_CHATS = 50;

    /**
     * Сообщения старше этого считаются устаревшими при возврате связи.
     * Человек, вернувшийся через 6 часов, не отвечает на всё подряд —
     * он видит новые сообщения и живёт дальше, а старое пропускает.
     */
    private static final long STALE_MESSAGE_SECONDS = 6 * 3600L;

    /** Сколько ждём, пока начатый ход договорит. Дольше — уже не остановка, а зависание. */
    private static final java.time.Duration SHUTDOWN_GRACE = java.time.Duration.ofMinutes(2);

    /** Насколько часто сообщение из заглушённого чата остаётся без внимания. */
    private static final double MUTED_IGNORE_CHANCE = 0.8;

    /**
     * Иногда она просто не отвечает на сообщение, которое не адресовано ей
     * лично: не зацепило, занята, не в настроении. Живой человек не отвечает
     * на всё подряд. Владелец и прямые ответы ей — исключения.
     */
    private static final double SOMETIMES_IGNORE_CHANCE = 0.08;

    /** Окно, в котором чат считается живым: последнее сообщение было только что. */
    private static final long ACTIVITY_WINDOW_SECONDS = 180;
    private Spontaneity spontaneity;
    private ru.rainedev.raine.core.PhraseCollector phraseCollector;
    private ru.rainedev.raine.core.EveningReflection eveningReflection;
    private ru.rainedev.raine.memory.Journal journal;
    private ru.rainedev.raine.memory.DeferredReplies deferredReplies;
    private ru.rainedev.raine.core.Mood mood;
    private ru.rainedev.raine.memory.Relationships relationships;
    private ru.rainedev.raine.memory.DiaryMemory diaryMemory;
    private LlmClient llmClient;

    public RaineApp(SimpleTelegramClientBuilder builder,
                    SimpleAuthenticationSupplier<?> authentication,
                    Config config) {
        this.config = config;
        delivery = new ru.rainedev.raine.telegram.TelegramDelivery(java.nio.file.Path.of("data/telegram_delivery.json"));
        // после заморозки аккаунта продолжать нельзя: каждая следующая попытка
        // только копит отказы
        ru.rainedev.raine.core.Fatal.handler(error -> {
            log.error("Останавливаюсь: учётная запись недоступна");
            stopEverything();
        });
        builder.addUpdateHandler(TdApi.UpdateAuthorizationState.class, this::onAuthorizationState);
        builder.addUpdateHandler(TdApi.UpdateConnectionState.class, this::onConnectionState);
        builder.addUpdateHandler(TdApi.UpdateNewMessage.class, this::onNewMessage);
        // Telegram подтверждает отправку отдельным событием и только там называет
        // настоящий номер сообщения
        builder.addUpdateHandler(TdApi.UpdateMessageSendSucceeded.class,
                update -> delivery.succeeded(update.oldMessageId, update.message));
        builder.addUpdateHandler(TdApi.UpdateMessageSendFailed.class,
                update -> delivery.failed(update.message.chatId, update.oldMessageId,
                        update.error == null ? "Unknown reason" : update.error.message));
        builder.addUpdateHandler(TdApi.UpdateStoryPostSucceeded.class,
                update -> delivery.storySucceeded(update.oldStoryId, update.story));
        builder.addUpdateHandler(TdApi.UpdateStoryPostFailed.class,
                update -> delivery.storyFailed(update.story.posterChatId, update.story.id,
                        update.error == null ? "Unknown reason" : update.error.message));
        this.client = builder.build(authentication);
    }

    public SimpleTelegramClient client() {
        return client;
    }

    /** Поднимает всё остальное. Вызывается после того, как клиент авторизовался. */
    public void start() {
        // веб-панель поднимается первой: даже если Telegram ещё не подключился,
        // уже видно, что процесс жив и что он пишет в лог
        try {
            webUi = ru.rainedev.raine.ui.WebUi.start(
                    config.webUiPort(),
                    java.nio.file.Path.of("logs/raine.log"),
                    config.camera().gallery());
            if (webUi != null) {
                webUi.customStatus(() -> "ok");
            }
        } catch (java.io.IOException e) {
            log.warn("Не удалось поднять веб-панель: {}", e.getMessage());
        }

        telegram = new Telegram(client, config.character().name(), delivery);
        if (delivery.connected()) telegram.reconcilePending();
        lockdown = new Lockdown(Lockdown.Mode.of(config.lockdown()), config.ownerId(),
                config.lockdownAllowChannels());
        log.info("Круг общения: {}", describe(lockdown.mode()));
        // без этого поиск по своим чатам возвращает пустоту: TDLib ищет
        // только по тому, что уже загружено с сервера
        telegram.loadChats(200);

        ru.rainedev.raine.llm.Transcript.enabled(config.llmTranscript());
        Prompts prompts = new Prompts(config.promptsDir(), config.character());
        this.llmClient = new OpenAiCompatibleClient(config.llm(), config.embedding());
        LlmClient llm = this.llmClient;

        // зрение подключается к разметке: описание встаёт прямо под вложением
        MediaDescriber media = MediaDescriber.NONE;
        if (config.vision().enabled()) {
            vision = new Vision(llm, prompts, config.vision().cacheDir(),
                    config.vision().model(), config.vision().cheapModel(), config.character().name());
            var hearing = new ru.rainedev.raine.speech.SpeechToText(config.hearing());
            if (hearing.isAvailable()) {
                vision.hearing(hearing);
            }
            telegramMedia = new TelegramMedia(telegram, vision, () -> loop == null ? List.of() : loop.context());
            telegramMedia.videoInChannels(config.vision().videoInChannels());
            media = telegramMedia;
        } else {
            log.info("Зрение выключено: вложения видны только по типу");
        }

        // Отношения создаём до форматировщика: пол собеседника берётся из её
        // собственных заметок, а не угадывается кодом по окончанию имени.
        relationships = new ru.rainedev.raine.memory.Relationships(
                java.nio.file.Path.of("data/relationships.json"));

        MessageFormatter formatter = new MessageFormatter(
            telegram::nameOf,
            (chatId, messageId) -> telegram.message(chatId, messageId),
            media,
            relationships::genderOf);

        actions = config.readOnly()
                ? new TelegramActions.ReadOnly()
                : new TelegramActions.Live(client, telegram);
        if (config.readOnly()) {
            log.warn("Режим только чтения: Raine думает, но ничего не отправляет и не открывает чаты");
        }

        AntiRepeat antiRepeat = new AntiRepeat(llm, prompts.lazy("anti_repeat.md"), config.repeat());

        // WebSearch создаём раньше, чтобы передать в TelegramTools
        WebSearch web = new WebSearch(config.webSearchUrl(), config.webSearchKey(), config.webSearchProvider());

        tools = new TelegramTools(telegram, actions, new ChatScreen(formatter, prompts).recentDepth(config.vision().recentDepth()),
                lockdown, antiRepeat, loop, web);
        if (telegramMedia != null) {
            tools.media(telegramMedia);
        }

        // выбор клиента генерации картинок: классическая очередь добровольцев
        // (aihorde.net) или прямой платный OpenAI-совместимый эндпоинт
        ru.rainedev.raine.image.ImageClient cameraClient;
        String cameraUrl = config.camera().baseUrl();
        if (cameraUrl != null && cameraUrl.contains("aihorde.net")) {
            cameraClient = new ru.rainedev.raine.image.HordeClient(
                    cameraUrl, config.camera().apiKey(), config.camera().models(),
                    java.time.Duration.ofMinutes(config.camera().queueMinutes()));
        } else {
            cameraClient = new ru.rainedev.raine.image.OpenAiImageClient(
                    cameraUrl, config.camera().apiKey(), config.camera().models());
        }
        if (config.camera().enabled() && cameraClient.isAvailable()) {
            tools.camera(new ru.rainedev.raine.image.ImageGenerator(cameraClient, llm, prompts,
                    config.camera().gallery(), config.character().name(),
                    config.vision().model(), config.camera().maxTrials()),
                    config.camera().gallery(), this::onPhotoOutcome);
        } else {
            log.info("Генерация снимков не настроена: сфотографироваться нечем");
        }

        var voiceGenerator = new ru.rainedev.raine.speech.VoiceGenerator(config.voice(), config.voice().directory());
        if (voiceGenerator.isAvailable()) {
            tools.voice(voiceGenerator, config.voice().directory(), prompts.lazy("record_audio_speech.md"));
        } else {
            log.info("Синтез речи не настроен: голосовые записывать нечем");
        }

        Diary diary = new Diary(config.diaryDir());
        diary.embedder(llm::embedding);
        // личный дневник — отдельным слоем: по дням, от первого лица, для
        // рефлексии и материала постов в канал/истории
        journal = new ru.rainedev.raine.memory.Journal(
                config.diaryDir().resolveSibling("journal"));
        ru.rainedev.raine.memory.JournalWriter journalWriter =
                new ru.rainedev.raine.memory.JournalWriter(
                        journal, diary, llm, prompts.lazy("journal_reflect.md"));

        // ask доступен всегда: заглянуть в память можно в любой момент хода
        Toolbox always = new Toolbox();
        Recall recall = new Recall(diary, llm, prompts.lazy("character_base.md"), web);
        always.add(recall.asTool(this::currentSituation));
        if (config.behaviour().stickers()) {
            always.add(tools.stickers().list());
            // пак открывают не в чате, а «у себя»: ссылка могла прийти когда угодно
            always.add(tools.stickers().pack());
            // забрать пак, из которого пришёл чужой стикер — это всегда доступно
            always.add(tools.stickers().savePack());
        }
        always.add(tools.chatList());
        always.add(tools.contactList());
        always.add(tools.saveContact());
        always.add(tools.searchChats());
        always.add(tools.openById());
        // поиск и подписка на каналы/группы: она сама решает, на кого
        // подписаться — увидела автора в TikTok, заинтересовалась, нашла
        always.add(tools.findChat());
        always.add(tools.joinChat());
        // управление собственным профилем: имя, описание, аватарка — как у
        // человека, который зашёл в настройки и поменял то, что захотел
        always.add(tools.myProfile());
        always.add(tools.setMyBio());
        always.add(tools.setMyName());
        always.add(tools.setMyPhoto());
        always.add(tools.clearMyPhoto());
        // гифки: сохранять чужие и отправлять свои — как со стикерами
        always.add(tools.gifList());
        // коробка мемов и истории доступны всегда: кинуть мем или ответить
        // на сторис можно из любого места, не только из открытого чата
        always.add(tools.memeList());
        always.add(tools.storyList());
        always.add(tools.replyToStory());

        WorkingMemory workingMemory = new WorkingMemory(config.workingMemoryFile(), llm, config.character().name());
        around = new ru.rainedev.raine.core.Surroundings(
                config.around().enabled(), config.around().latitude(), config.around().longitude());
        phraseCollector = new ru.rainedev.raine.core.PhraseCollector(
                java.nio.file.Path.of("data/learned_phrases.txt"), telegram.myId());
        var pendingReplies = new ru.rainedev.raine.memory.PendingReplies(
                java.nio.file.Path.of("data/unanswered.txt"));
        tools.pendingReplies(pendingReplies);
        deferredReplies = new ru.rainedev.raine.memory.DeferredReplies(
                java.nio.file.Path.of("data/deferred.txt"));
        tools.deferredReplies(deferredReplies);
        mood = new ru.rainedev.raine.core.Mood(random);
        tools.rhythmSpeed(mood::typingSpeedFactor);
        tools.relationships(relationships);
        // коробка мемов: описание берём тем же зрением, что и для картинок
        tools.memeBox(new ru.rainedev.raine.tools.MemeLibrary(
                java.nio.file.Path.of("data/memes"), telegram,
                file -> vision == null ? "" : vision.describe(file, Vision.Kind.PHOTO, List.of())));

        // текущая ситуация: время, погода, самочувствие, настроение, что делает.
        // Используется для фото (чтобы селфи отражало реальность — маска при
        // болезни, пижама вечером, рюкзак в колледже) и в любых других местах,
        // где ей нужно знать, где она и что с ней
        tools.currentSituation(() -> {
            StringBuilder s = new StringBuilder();
            String env = around.asPromptSuffix();
            if (!env.isEmpty()) {
                s.append(env).append('\n');
            }
            String wm = workingMemory.read();
            if (!wm.isEmpty()) {
                s.append("<current_state>\n").append(wm).append("\n</current_state>\n");
            }
            return s.toString();
        });

        loop = new NotificationLoop(
                llm,
                () -> {
                    String sample = phraseCollector.sample();
                    String suffix = around.asPromptSuffix() + mood.asPromptSuffix()
                            + workingMemory.asPromptSuffix();
                    if (!sample.isEmpty()) {
                        suffix += "<overheard_recently>\n"
                                + "These are phrases real people wrote recently - in your chats and in the "
                                + "comments you read: how they joke, roast and insult each other, flirt, "
                                + "argue, make up, how close or cold they are, who's mad at whom, and how "
                                + "they tell a joke from a serious take. Absorb all of it: intonations, "
                                + "jokes, slang, energy, the way they tease and the words they throw "
                                + "around. Don't copy verbatim, but let it color how you text, and keep "
                                + "folding what you like into your own style over time - you grow from "
                                + "what real people write, not only from your rules. Match the register of "
                                + "the people you're with, and note the relationships you observe.\n"
                                + sample
                                + "</overheard_recently>\n";
                    }
                    return prompts.system(suffix);
                },
                always, config.contextTokenLimit());
        loop.transport(delivery::ready, delivery::generation, delivery::bindGeneration,
                () -> { tools.reconcileConfirmed(); return delivery.statusPrompt(); },
                () -> {
                    delivery.clearGeneration();
                    telegram.invalidateChatCache();
                    catchUpOnUnread();
                });
        // когда Telegram падает, она живёт свой день: фото, дневник, браузер,
        // мысли — всё, что не требует мессенджера. Telegram-действия отсечены
        // на уровне инструментов, поэтому ложных успехов не будет
        loop.offlineLife(config.offlineLife());
        loop.onTransportResumed(this::drainOfflineIncoming);
        this.diaryMemory = new DiaryMemory(diary, llm,
                config.diaryInjectionMaxLength(), config.diaryMinRelatedness());
        loop.memory(this.diaryMemory);
        DiaryWriter writer = new DiaryWriter(
                diary, llm, prompts.lazy("diary_save.md"), config.diaryPlagiarismThreshold());
        pending = new ru.rainedev.raine.memory.PendingConversations(config.diaryDir().resolveSibling("pending"));
        // разговор не выбрасываем, а переносим в долгую память
        rememberConversation = why -> {
            if (loop.context().isEmpty()) {
                return;
            }
            log.info("{} — переношу разговор в дневник", why);
            try {
                // сначала средняя память: ей нужен ещё не урезанный разговор
                workingMemory.update(prompts.system(""), loop.context());
                writer.saveStrict(prompts.system(""), loop.context());
            } catch (RuntimeException e) {
                // чаще всего это оборванная сеть на остановке: пересказ делает
                // модель, а её нет. Разговор откладывается сырым и разбирается
                // при следующем запуске — потерять целый день из-за связи нельзя
                log.error("Не удалось сохранить память — откладываю разговор", e);
                pending.keep(loop.context());
            }
            loop.clearContext();
            diaryMemory.forget();
            diary.reload();
        };
        loop.onContextOverflow(() -> rememberConversation.accept("Контекст переполнен"));

        // ход закончен — выходим из чатов, в которые заходили
        loop.onNotificationDone(done -> tools.closeOpened());
        loop.onNotificationFailed((notification, failure) ->
                pending.keepFailure(notification.text(), loop.context(), String.valueOf(failure.getMessage())));

        rest = new Rest(random);
        loop.rest(rest);

        // всё, что можно поменять, не перезапуская бота
        settings = new ru.rainedev.raine.config.LiveConfig(Config.defaultFile(), config)
                .onChange(fresh -> {
                    var circle = ru.rainedev.raine.telegram.Lockdown.Mode.of(fresh.lockdown());
                    if (circle != lockdown.mode() || fresh.lockdownAllowChannels() != lockdown.allowsChannels()) {
                        log.info("Круг общения теперь: {}{}", describe(circle),
                                fresh.lockdownAllowChannels() ? ", каналы читаю" : ", каналы не читаю");
                    }
                    tools.behaviour(fresh.behaviour());
                    rest.dayNaps(fresh.behaviour().dayNaps());
                    lockdown.mode(ru.rainedev.raine.telegram.Lockdown.Mode.of(fresh.lockdown()));
                    lockdown.allowChannels(fresh.lockdownAllowChannels());
                    antiRepeat.limits(fresh.repeat());
                    diaryMemory.limits(fresh.diaryInjectionMaxLength(), fresh.diaryMinRelatedness());
                    ru.rainedev.raine.llm.Transcript.enabled(fresh.llmTranscript());
                });
        settings.watch();

        if (config.night().enabled()) {
            var night = new ru.rainedev.raine.core.NightSleep(
                    config.night().bedtimeFrom(), config.night().bedtimeTo(),
                    config.night().wakeFrom(), config.night().wakeTo(),
                    java.time.Clock.systemDefaultZone(), random);
            rest.night(night);
            log.info("Сегодня ложится около {}, встаёт около {}",
                    night.bedtime().toLocalTime().withSecond(0), night.wakeTime().toLocalTime().withSecond(0));
        }

        rest.duringNight((duration, wokeUp) -> {
            Config fresh = settings.current();
            if (!fresh.sleepConsolidation()) return;
            var limits = fresh.consolidation();
            var bounded = new ru.rainedev.raine.memory.SleepConsolidation(
                    diary, llm, prompts.lazy("sleep_consolidator.md"),
                    config.diaryDir().resolve("archive"), fresh.diaryConsolidationBudget(), random,
                    limits.maxRequests(), limits.maxTokens());
            bounded.run(duration.compareTo(java.time.Duration.ofMinutes(limits.minutes())) < 0
                    ? duration : java.time.Duration.ofMinutes(limits.minutes()),
                    () -> wokeUp.getAsBoolean() || !settings.current().sleepConsolidation());
        });
        log.info("Пересмотр памяти во сне: {}", config.sleepConsolidation() ? "включён" : "выключен");

        // тело браузера: живой Firefox, чтобы сидеть на сайтах и в соцсетях.
        // Полные наборы инструментов живут отдельно и выдаются двумя путями:
        // в порыв свободного времени и по её собственному желанию — #open_browser
        // доступен в любом ходе, но раскрывает остальные инструменты только когда
        // она сама решила пойти в сеть (иначе список раздувался бы на каждом ходу).
        // Автоматизация TikTok нарушает его правила и может привести к бану,
        // поэтому объёмы намеренно маленькие, а пределы — в TikTokLimits.
        Toolbox browserBox = null;
        ru.rainedev.raine.tiktok.TikTokLimits tiktokLimits = null;
        if (config.browser().enabled()) {
            browser = new ru.rainedev.raine.browser.Browser(config.browser());
            var humanInput = new ru.rainedev.raine.browser.HumanInput(random);
            var browserTools = new ru.rainedev.raine.browser.BrowserTools(browser, humanInput, vision,
                    java.nio.file.Path.of("data/browser/bookmarks.md"), config.camera().gallery());
            // свой локальный набор: browserBox переприсваивается, а в лямбде
            // нужна неизменяемая ссылка
            final Toolbox box = new Toolbox();
            browserTools.tools().forEach(box::add);
            List<ru.rainedev.raine.core.Tool> alongside = new java.util.ArrayList<>();
            if (config.tiktok().enabled()) {
                tools.videoDir(config.tiktok().downloadDir());
                tiktokLimits = new ru.rainedev.raine.tiktok.TikTokLimits(
                        config.tiktok(), java.nio.file.Path.of("data/tiktok_limits.properties"));
                var tiktokTools = new ru.rainedev.raine.tiktok.TikTokTools(
                        browser, humanInput, vision, config.tiktok(), tiktokLimits, random);
                // прочитанные комментарии идут в коллекцию живой речи наравне
                // с чужими сообщениями: манере она учится у людей, а не у правил
                tiktokTools.phrases(phraseCollector);
                tiktokTools.tools().forEach(tool -> {
                    box.add(tool);
                    alongside.add(tool);
                });
                // тот же набор пригодится, когда ролик кидают в личку (см. notifyAbout)
                tiktokBox = box;
                log.info("TikTok подключён: до {} роликов за заход, разогрев {} дн., охлаждение {} мин",
                        config.tiktok().maxVideosPerSession(), config.tiktok().warmupDays(),
                        config.tiktok().cooldownMinutes());
            } else {
                log.info("Браузер включён, TikTok выключен — она ходит по сети сама");
            }
            browserBox = box;
            // дверь в браузер — всегда под рукой: куда идти, решает она
            always.add(browserTools.start(alongside));
        } else {
            log.info("Браузер выключен: она живёт только в Telegram");
        }

        spontaneity = new Spontaneity(loop, diary, always, random);
        spontaneity.webSearch(web);
        spontaneity.journal(journal);
        spontaneity.pendingReplies(pendingReplies);
        spontaneity.deferredReplies(deferredReplies);
        spontaneity.asleepWhen(rest::isResting);
        // привычка листать TikTok в свободное время: отдельный набор инструментов,
        // старт только когда аккаунт не на охлаждении, а по окончании сессии —
        // включаем охлаждение, чтобы заходы были редкими и человеческими
        final Toolbox scrollBox = browserBox;
        final ru.rainedev.raine.tiktok.TikTokLimits limitsRef = tiktokLimits;
        if (scrollBox != null && limitsRef != null) {
            spontaneity.tiktok(scrollBox, () -> !limitsRef.coolingDown(),
                    limitsRef::usage, limitsRef::sessionEnded);
        }
        // порыв «посидеть в сети» — без TikTok и без сценария: куда идти, она
        // выбирает сама, по своим закладкам или любопытству
        if (scrollBox != null) {
            spontaneity.browser(scrollBox);
        }
        tools.actingOnImpulse(spontaneity::isActing);
        spontaneity.start();

        // подключаем веб-панель ко всем компонентам для дашборда
        if (webUi != null) {
            webUi.loop(loop)
                    .relationships(relationships)
                    .diary(diary)
                    .workingMemory(workingMemory)
                    .mood(mood)
                    .phraseCollector(phraseCollector)
                    .diaryMemory(diaryMemory)
                    .surroundings(around)
                    .telegram(telegram)
                    .llmClient(llmClient);
        }

        // вечерний дневник: пишет от первого лица, что было за день
        eveningReflection = new ru.rainedev.raine.core.EveningReflection(
                journalWriter,
                () -> prompts.system(around.asPromptSuffix() + workingMemory.asPromptSuffix()),
                loop::context,
                random);
        eveningReflection.asleepWhen(rest::isResting);
        eveningReflection.busyWhen(spontaneity::isActing);
        eveningReflection.start();

        // «в сети» держится только пока она занята перепиской: остальное время
        // и весь сон её видно офлайн, как любого человека
        loop.onIdle(() -> actions.setOnline(false));
        rest.onStateChange(resting -> actions.setOnline(false));

        brain = Thread.ofVirtual().name("raine-brain").start(loop::run);
        Thread.ofVirtual().name("raine-pending").start(() -> rememberPending(writer, prompts.system("")));
        log.info("Raine запущена от имени {} (id {})", config.character().name(), telegram.myId());
    }

    /**
     * Отложенное сетью досказывается при первой возможности — не мешая запуску
     * и не задерживая ответ. Не получилось и сейчас — пусть лежит дальше:
     * лучше пересказать завтра, чем выбросить сегодня.
     */
    private void rememberPending(DiaryWriter writer, String systemPrompt) {
        for (java.nio.file.Path file : pending.waiting()) {
            var conversation = pending.read(file);
            if (conversation.isEmpty()) {
                log.warn("Отложенный разговор {} пуст или повреждён — оставляю для проверки", file.getFileName());
                continue;
            }
            try {
                int saved = writer.saveStrict(systemPrompt, conversation).size();
                pending.done(file);
                log.info("Отложенный разговор {} пересказан, записей: {}", file.getFileName(), saved);
            } catch (RuntimeException e) {
                log.warn("Отложенный разговор {} пока не пересказать: {}", file.getFileName(), e.getMessage());
                return;   // связи по-прежнему нет, остальные ждут своей очереди
            }
        }
    }

    /**
     * Снимок готовится в фоне, поэтому о нём сообщают уведомлением — так же,
     * как о пришедшем сообщении. Иначе результат некуда было бы деть: ход,
     * в котором она начала съёмку, давно закончился.
     */
    private void onPhotoOutcome(long chatId, boolean ready, String message) {
        String text = ready
                ? ("Your photo is ready.\n\n%s\nFilename: %s\n\nOpen the chat and send it with "
                   + "send_telegram_message if you like it, or take another one. Mention the filename "
                   + "in your diary — you may want to reuse the photo later.")
                        .formatted(lookAtOwnPhoto(message), message)
                : "Your photo did not work out: " + message + ". You can try again or let it go.";

        Toolbox available = new Toolbox();
        available.add(tools.open(chatId));
        loop.submit(new Notification(text, available, true));
        log.info("Уведомление о снимке поставлено в очередь");
    }

    /** Последняя реплика в контексте — чтобы подагент искал по делу, а не вообще. */
    /**
     * Собственный снимок она сначала разглядывает, а потом решает, отправлять ли.
     * Без описания выбор делается вслепую: «файл готов» — и всё.
     */
    private String lookAtOwnPhoto(String fileName) {
        if (vision == null) {
            return "";
        }
        try {
            return vision.describe(config.camera().gallery().resolve(fileName),
                    Vision.Kind.PHOTO, loop == null ? List.of() : loop.context());
        } catch (RuntimeException e) {
            log.debug("Свой снимок не разглядеть: {}", e.getMessage());
            return "";
        }
    }

    private String currentSituation() {
        return loop.latestContextText();
    }

    private void onAuthorizationState(TdApi.UpdateAuthorizationState update) {
        if (update.authorizationState instanceof TdApi.AuthorizationStateClosing
                || update.authorizationState instanceof TdApi.AuthorizationStateClosed
                || update.authorizationState instanceof TdApi.AuthorizationStateLoggingOut) delivery.connection(false);
        switch (update.authorizationState) {
            case TdApi.AuthorizationStateReady ignored -> log.info("Авторизация пройдена");
            case TdApi.AuthorizationStateClosing ignored -> {
                telegramClosing = true;
                log.info("Закрываемся...");
            }
            case TdApi.AuthorizationStateClosed ignored -> {
                telegramClosing = true;
                log.info("Закрыто");
            }
            case TdApi.AuthorizationStateLoggingOut ignored -> log.info("Выходим из аккаунта...");
            default -> log.debug("Состояние авторизации: {}",
                    update.authorizationState.getClass().getSimpleName());
        }
    }

    /** Состояние связи стоит видеть в логе: половина бед — с сетью, а не с ботом. */
    private void onConnectionState(TdApi.UpdateConnectionState update) {
        boolean ready = !telegramClosing && update.state instanceof TdApi.ConnectionStateReady;
        delivery.connection(ready);
        if (ready && telegram != null) {
            Thread.ofVirtual().name("raine-delivery-reconcile").start(telegram::reconcilePending);
        }
        switch (update.state) {
            case TdApi.ConnectionStateReady ignored -> log.info("Связь с Telegram есть");
            case TdApi.ConnectionStateConnecting ignored ->
                    log.info("Подключаюсь к Telegram... если надолго — проверь VPN или прокси");
            case TdApi.ConnectionStateConnectingToProxy ignored -> log.info("Подключаюсь через прокси...");
            case TdApi.ConnectionStateWaitingForNetwork ignored -> log.info("Жду сеть...");
            case TdApi.ConnectionStateUpdating ignored -> log.info("Догоняю пропущенное...");
            default -> log.debug("Состояние связи: {}", update.state.getClass().getSimpleName());
        }
    }

    /**
     * События Telegram разносит один поток, и он же доставляет ответы на наши
     * запросы. Спросить у него что-нибудь и подождать ответа прямо здесь —
     * значит остановить его навсегда: ответа он принести не сможет, потому что
     * ждёт сам себя. После такого клиент глохнет целиком, молча.
     * <p>
     * Поэтому здесь только то, что считается на месте, а всё остальное —
     * включая проверку круга общения — уходит на отдельный поток.
     */
    private void onNewMessage(TdApi.UpdateNewMessage update) {
        TdApi.Message message = update.message;
        if (message.isOutgoing) {
            return;
        }
        if (tools != null) tools.observeIncoming(message);
        long senderId = message.senderId instanceof TdApi.MessageSenderUser user ? user.userId : 0L;
        if (senderId == config.ownerId() && rest != null) {
            rest.wakeUp();   // сообщение владельца поднимает, даже если она отошла
        }
        if (loop == null || !loop.available()) {
            deferIncoming(message);
            return;
        }
        Thread.ofVirtual().name("raine-incoming").start(() -> {
            try {
                receive(message, senderId);
            } catch (RuntimeException e) {
                log.error("Не удалось разобрать входящее", e);
            }
        });
    }

    private void deferIncoming(TdApi.Message message) {
        synchronized (offlineIncoming) {
            TdApi.Message previous = offlineIncoming.get(message.chatId);
            if (previous == null || message.id > previous.id) offlineIncoming.put(message.chatId, message);
            while (offlineIncoming.size() > CATCH_UP_CHATS) {
                offlineIncoming.remove(offlineIncoming.keySet().iterator().next());
            }
        }
    }

    private void receive(TdApi.Message message, long senderId) {
        log.debug("Входящее из чата {} от {}", message.chatId, senderId);
        // собеседник написал — значит он ответил (или хотя бы проявился),
        // и долг по неотвеченному сообщению закрыт. Заодно отменяем
        // отложенный ответ, если он был: она ответит в рамках нового разговора
        tools.observeIncoming(message);
        if (!loop.available()) { deferIncoming(message); return; }
        // каждое входящее — материал для обучения: живые фразы собеседников
        // подмешиваются потом в промпт, чтобы она перенимала манеру общения
        if (phraseCollector != null) {
            try {
                phraseCollector.observe(message);
            } catch (RuntimeException e) {
                log.debug("Не удалось разобрать фразу: {}", e.getMessage());
            }
        }
        if (senderId != 0) {
            // Пол кодом не угадывается: ни по окончанию имени («Никита» → женский),
            // ни по счётчику «я пошёл/я пошла». Его определяет сама модель через
            // #note_about_person с параметром gender=, а код только хранит.
            // близость растёт от самого факта общения: код не оценивает
            // человека, он только считает контакты
            if (relationships != null) {
                try {
                    relationships.touch(senderId, telegram.nameOf(senderId));
                } catch (RuntimeException e) {
                    log.debug("Не удалось обновить отношения: {}", e.getMessage());
                }
            }
        }
        // судим по чату, а не только по отправителю: в разрешённой группе пишут
        // и незнакомые, и это нормально — переписка там общая
        if (!lockdown.allows(telegram.chatCached(message.chatId), telegram.isContact(senderId))) {
            log.debug("Пропускаю сообщение из чата {} — он вне круга общения", message.chatId);
            return;
        }

        // ответы ей самой и упоминания нельзя терять даже в заглушённом чате:
        // живой человек не игнорирует, когда его зовут по имени или отвечают
        // на его реплику
        boolean addressedToHer = addressedToHer(message);

        // заглушённый чат — просьба уделять ему меньше внимания, а не игнорировать
        // совсем: иначе о нём вообще ничего не узнать. Чтобы отсечь начисто,
        // чат убирают в архив
        if (!addressedToHer && telegram.isMuted(message.chatId) && random.nextDouble() < MUTED_IGNORE_CHANCE) {
            log.debug("Заглушённый чат {} — на этот раз пропускаю", message.chatId);
            return;
        }

        // иногда она просто не отвечает: сообщение не зацепило, она занята,
        // не в настроении. Живой человек не отвечает на всё подряд.
        // Исключения: владелец, ответы ей самой, упоминания — на это всегда
        if (!addressedToHer && senderId != config.ownerId()
                && random.nextDouble() < SOMETIMES_IGNORE_CHANCE) {
            log.debug("Пропускаю сообщение из чата {} — не в этот раз", message.chatId);
            return;
        }

        if (handledAsCommand(message, senderId)) {
            return;
        }
        notifyAbout(message, senderId, addressedToHer);
    }

    /**
     * Обращено ли сообщение к ней: ответ на её собственную реплику или непрочитанное
     * упоминание в группе. По этому определяется, насколько быстро надо реагировать.
     */
    private boolean addressedToHer(TdApi.Message message) {
        if (message.replyTo instanceof TdApi.MessageReplyToMessage reply) {
            try {
                TdApi.Message original = telegram.message(
                        reply.chatId == 0 ? message.chatId : reply.chatId, reply.messageId).orElse(null);
                if (original != null && original.senderId instanceof TdApi.MessageSenderUser sender
                        && sender.userId == telegram.myId()) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // недоступно — не страшно, просто реакция будет медленнее
            }
        }
        try {
            TdApi.Chat chat = telegram.chatCached(message.chatId);
            if (chat.unreadMentionCount > 0) {
                return true;
            }
        } catch (RuntimeException ignored) { }
        return false;
    }

    /**
     * Пауза перед тем, как взяться за уведомление. Живой человек замечает
     * уведомление не сразу, но когда ему отвечают или зовут — реагирует быстро.
     * Задержка поэтому зависит от того, насколько адресовано сообщение лично ей
     * и насколько свежий разговор.
     */
    private void noticeLater(TdApi.Message message, boolean addressedToHer) {
        int maxSeconds = settings == null ? config.noticeDelaySeconds() : settings.current().noticeDelaySeconds();
        if (maxSeconds <= 0) {
            return;
        }
        int hour = java.time.LocalTime.now().getHour();
        boolean isNight = hour >= 2 && hour < 6;

        // ночь (2-6 утра): она спит или полусонная. На адресованное ей
        // отвечает через 3-10 минут (как человек, который проснулся от
        // вибрации). На всё остальное — почти всегда не отвечает до утра.
        if (isNight && !addressedToHer) {
            if (random.nextDouble() < 0.85) {
                log.debug("Ночь — не отвечаю на неадресованное сообщение");
                return;   // просто игнорируем до утра
            }
        }

        int bound;
        if (addressedToHer) {
            if (isNight) {
                // ночью даже на прямое сообщение отвечает медленно — просыпается
                bound = 180 + random.nextInt(420);   // 3-10 мин
            } else {
                // ей ответили или позвали — почти мгновенно, как у живого человека с телефоном в руке
                bound = Math.min(3, maxSeconds);
            }
        } else if (recentActivity(message.chatId)) {
            // разговор живой: последнее сообщение было только что
            bound = Math.min(6, maxSeconds);
        } else {
            // не адресовано ей и чат не активный — может заметить и через полчаса,
            // и через два часа. Живой человек не сидит в телефоне постоянно.
            // Ночью минимальная задержка больше — телефон в другой комнате
            int minDelay = isNight ? Math.min(300, Math.max(120, maxSeconds))
                    : Math.min(30, maxSeconds);
            bound = minDelay + random.nextInt(Math.max(1, maxSeconds - minDelay));
        }
        if (bound <= 0) {
            return;
        }
        try {
            Thread.sleep(java.time.Duration.ofMillis(random.nextInt(bound * 1000)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** В чате было сообщение в последние минуты — значит разговор активный. */
    private boolean recentActivity(long chatId) {
        try {
            TdApi.Chat chat = telegram.chatCached(chatId);
            if (chat.lastMessage == null) {
                return false;
            }
            long ageSeconds = java.time.Instant.now().getEpochSecond() - chat.lastMessage.date;
            return ageSeconds >= 0 && ageSeconds < ACTIVITY_WINDOW_SECONDS;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Разбирает непрочитанное, накопившееся пока её не было.
     * <p>
     * Telegram не присылает уведомления о том, что пришло до запуска. Без этого
     * всё, написанное за ночь или за время простоя, остаётся незамеченным
     * навсегда — она просто не узнает, что ей писали.
     */
    private void catchUpOnUnread() {
        try {
            // от старых к новым: так разговор восстанавливается в том порядке,
            // в каком он шёл
            delivery.requireReady();
            List<TdApi.Chat> chats = new java.util.ArrayList<>(telegram.chats(CATCH_UP_CHATS));
            java.util.Collections.reverse(chats);

            int found = 0;
            int stale = 0;
            long nowSeconds = System.currentTimeMillis() / 1000L;
            for (TdApi.Chat chat : chats) {
                if (chat.unreadCount == 0 || chat.lastMessage == null || chat.lastMessage.isOutgoing) {
                    continue;
                }
                // устаревшие сообщения пропускаем: человек не отвечает залпом
                // на всё, что пришло за полдня оффлайна
                if (nowSeconds - chat.lastMessage.date > STALE_MESSAGE_SECONDS) {
                    stale++;
                    continue;
                }
                long senderId = chat.lastMessage.senderId instanceof TdApi.MessageSenderUser user ? user.userId : 0L;
                if (!lockdown.allows(chat, telegram.isContact(senderId))) {
                    continue;
                }
                tools.observeIncoming(chat.lastMessage);
                notifyAbout(chat.lastMessage, senderId, addressedToHer(chat.lastMessage), false);
                found++;
            }
            if (found > 0 || stale > 0) {
                log.info("Пока меня не было: {} новых сообщений, {} устаревших пропущено", found, stale);
            }
        } catch (RuntimeException e) {
            log.warn("Не удалось разобрать непрочитанное: {}", e.getMessage());
            throw e;
        }
    }

    private void drainOfflineIncoming() {
        List<TdApi.Message> delayed;
        synchronized (offlineIncoming) { delayed = List.copyOf(offlineIncoming.values()); }
        for (TdApi.Message message : delayed) {
            if (!loop.available()) return;
            try {
                long sender = message.senderId instanceof TdApi.MessageSenderUser user ? user.userId : 0;
                tools.observeIncoming(message);
                if (lockdown.allows(telegram.chat(message.chatId), telegram.isContact(sender))) {
                    notifyAbout(message, sender, addressedToHer(message), false);
                }
                if (!loop.available()) return;
                synchronized (offlineIncoming) { offlineIncoming.remove(message.chatId, message); }
            } catch (RuntimeException e) {
                log.warn("Deferred incoming chat {} remains pending: {}", message.chatId, e.getMessage());
                return;
            }
        }
    }

    /** Команды владельца — короткие служебные вопросы, ради них будить модель незачем. */
    private boolean handledAsCommand(TdApi.Message message, long senderId) {
        if (senderId != config.ownerId() || !(message.content instanceof TdApi.MessageText text)) {
            return false;
        }
        String command = text.text.text.strip().toLowerCase();
        if (!command.equals("/version")) {
            return false;
        }
        String answer = "Raine %s, Java %s, дневник: %d записей"
                .formatted(version(), Runtime.version().feature(), new Diary(config.diaryDir()).size());
        actions.sendMessage(message.chatId, answer, message.id);
        log.info("Ответ на команду: {}", answer);
        return true;
    }

    private static String version() {
        String version = RaineApp.class.getPackage().getImplementationVersion();
        return version == null ? "из исходников" : version;
    }

    /**
     * Ход «тебе кинули ролик в TikTok»: даёт ей инструменты TikTok и ссылку,
     * чтобы открыть и отреагировать в самом TikTok. Пределы TikTokLimits
     * (разогрев, суточные потолки) зашиты в инструменты и действуют и здесь.
     */
    private void notifyAboutTiktok(TdApi.Chat chat, long senderId, String url, TdApi.Message message) {
        loop.discardMatching(Notifications.openingTag(chat.id));
        String body = Notifications.tiktokReaction(chat, telegram.nameOf(senderId), url, messageText(message));
        Notification notification = new Notification(body, new Toolbox(tiktokBox.all().toArray(ru.rainedev.raine.core.Tool[]::new)));
        if (senderId == config.ownerId()) {
            loop.submitFirst(notification);
        } else {
            loop.submit(notification);
        }
        log.info("В личке кинули TikTok — она откроет ролик и отреагирует в самом TikTok");
    }

    /** Ссылка на ролик TikTok в тексте сообщения, если она там есть. */
    private static String findTikTokLink(TdApi.Message message) {
        String text = messageText(message);
        if (text.isBlank()) {
            return null;
        }
        java.util.regex.Matcher matcher = TIKTOK_LINK.matcher(text);
        return matcher.find() ? matcher.group() : null;
    }

    /** Текст текстового сообщения; для всего остального — пусто. */
    private static String messageText(TdApi.Message message) {
        return message.content instanceof TdApi.MessageText text ? text.text.text : "";
    }

    private void notifyAbout(TdApi.Message message, long senderId, boolean addressedToHer) {
        notifyAbout(message, senderId, addressedToHer, true);
    }

    private void notifyAbout(TdApi.Message message, long senderId, boolean addressedToHer, boolean delay) {
        // человек не бросается к телефону в ту же секунду: он замечает уведомление
        // не сразу. Но когда ему отвечают — реагирует почти мгновенно: задержка
        // зависит от того, насколько сообщение адресовано лично ей
        if (delay) noticeLater(message, addressedToHer);
        if (!delivery.ready()) { deferIncoming(message); return; }

        TdApi.Chat chat = telegram.chat(message.chatId);
        ChatKind kind = ChatKind.of(chat);

        // ссылку на ролик в личке она открывает и реагирует в самом TikTok, не
        // отвечая в чате; в группах так не делаем — только личные сообщения
        if (kind == ChatKind.DM && tiktokBox != null) {
            String link = findTikTokLink(message);
            if (link != null) {
                notifyAboutTiktok(chat, senderId, link, message);
                return;
            }
        }

        String text = switch (kind) {
            case DM -> Notifications.directMessage(chat);
            case GROUP -> Notifications.groupMessage(chat, telegram.nameOf(senderId), senderId);
            case CHANNEL -> Notifications.channelPost(chat);
        };

        // уведомление устарело, если такое же уже стоит в очереди по этому чату
        loop.discardMatching(Notifications.openingTag(chat.id));

        // набор живёт вместе с уведомлением: инструменты одного чата не должны
        // оставаться доступными, когда разговор идёт уже в другом
        Toolbox available = new Toolbox();
        available.add(tools.open(chat.id));

        // сообщение владельца и закреплённые чаты идут вне очереди: остальное
        // подождёт, а это — то, ради чего она вообще здесь
        boolean important = senderId == config.ownerId()
                || (behaviour().wakeOnPinnedChat() && telegram.isPinned(chat.id));
        if (important) {
            loop.submitFirst(new Notification(text, available));
            log.info("Уведомление из чата \"{}\" — вне очереди", chat.title);
        } else {
            loop.submit(new Notification(text, available));
            log.info("Уведомление из чата \"{}\" поставлено в очередь", chat.title);
        }
    }

    private static String describe(Lockdown.Mode mode) {
        return switch (mode) {
            case NONE -> "все";
            case CONTACTS_ONLY -> "только контакты";
            case OWNER_ONLY -> "только владелец";
        };
    }

    /** Повадки берутся из живых настроек: их правят чаще всего. */
    private Config.Behaviour behaviour() {
        return settings == null ? config.behaviour() : settings.current().behaviour();
    }

    /** Остановка по непоправимой причине: разбудить main, чтобы процесс завершился. */
    private void stopEverything() {
        try {
            close();
        } catch (Exception e) {
            log.warn("При остановке что-то пошло не так", e);
        }
    }

    /**
     * Остановка по-человечески: начатый ход доводится до конца, а всё сказанное
     * за сеанс уходит в память. Оборвать посреди хода — значит потерять разговор
     * целиком: он живёт в оперативной памяти до самой записи в дневник.
     */
    @Override
    public void close() throws Exception {
        if (!closing.compareAndSet(false, true)) {
            return;   // остановка может прийти и сигналом, и из main разом
        }
        log.info("Останавливаюсь. Дайте время сохранить разговор");

        if (webUi != null) {
            try { webUi.close(); } catch (RuntimeException ignored) { }
        }
        if (settings != null) {
            settings.close();
        }
        if (eveningReflection != null) {
            eveningReflection.close();
        }
        if (spontaneity != null) {
            spontaneity.close();   // новых порывов не заводим
        }
        if (loop != null) {
            loop.stop();
            if (!loop.awaitStopped(SHUTDOWN_GRACE)) {
                log.warn("Ход не успел закончиться за {} — сохраняю как есть", SHUTDOWN_GRACE);
            }
            rememberConversation.accept("Останавливаюсь");
        }
        // браузер закрываем после цикла: к этому времени никакой ход уже не
        // держит страницу, и Chromium можно гасить, не обрывая действие
        if (browser != null) {
            browser.close();
        }
        // прощаемся с Telegram, только если он ещё слушает: он закрывается сам
        // и своим чередом, а вызов в закрывающийся клиент повисает надолго
        if (!telegramClosing) {
            if (tools != null) {
                tools.closeOpened();   // выходим из чатов, а не бросаем их открытыми
            }
            if (actions != null) {
                actions.setOnline(false);
            }
        } else {
            log.debug("Telegram уже закрывается — прощаться через него не буду");
        }
        if (brain != null) {
            brain.interrupt();
        }
        // если Telegram закрывается сам, ждать от него подтверждения бессмысленно:
        // ответа не будет, а ожидание длится полторы минуты на пустом месте
        if (telegramClosing) {
            log.debug("Telegram закрывается сам — подтверждения не жду");
        } else {
            try {
                client.close();
            } catch (RuntimeException e) {
                log.debug("Telegram закрылся не попрощавшись: {}", e.getClass().getSimpleName());
            }
        }
        log.info("Всё сохранено, до встречи");
    }
}
