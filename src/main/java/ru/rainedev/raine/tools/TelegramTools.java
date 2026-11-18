package ru.rainedev.raine.tools;

import it.tdlight.jni.TdApi;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.core.LowQualityException;
import ru.rainedev.raine.core.NotificationLoop;
import ru.rainedev.raine.core.Numbers;
import ru.rainedev.raine.core.Tool;
import ru.rainedev.raine.core.Toolbox;
import ru.rainedev.raine.phone.ChatKind;
import ru.rainedev.raine.phone.ChatScreen;
import ru.rainedev.raine.phone.MessageFormatter;
import ru.rainedev.raine.telegram.Telegram;
import ru.rainedev.raine.telegram.TelegramActions;
import ru.rainedev.raine.tools.WebSearch;
import ru.rainedev.raine.ui.WebUi;

/** Инструменты для работы с Telegram. Состав зависит от того, где Raine сейчас находится. */
public final class TelegramTools {

    private static final Logger log = LoggerFactory.getLogger(TelegramTools.class);

    /** Длиннее — уже не разговорное сообщение, а простыня. */
    private static final int MAX_MESSAGE_LENGTH = 700;

    /** Столько сообщений подряд — уже не разговор, а поток. */
    private static final int MAX_IN_A_ROW = 10;

    /** Насколько настойчиво просить разбивать длинные реплики. */
    private static final double SPLIT_NUDGE = 0.3;

    /** Больше этого числа сообщений при открытии чата не подгружается никогда. */
    private static final int HISTORY_DEPTH = 30;

    /** Сколько чатов показывать в списке. */
    private static final int CHAT_LIST_DEPTH = 30;

    private static final int SEARCH_LIMIT = 20;

    /** Больше уже не поиск, а вываливание переписки в контекст. */
    private static final int MAX_SEARCH_LIMIT = 50;

    /** Сколько сообщений показывать вокруг найденного. */
    private static final int AROUND_DEPTH = 10;

    /** Шире окно делать нельзя: контекст затопит. */
    private static final int MAX_AROUND = 25;

    /** Столько стикеров помещается в избранном и недавних. */

    private static final int CONTACT_LIMIT = 50;

    /** Длиннее превью в списке чатов не нужно. */
    private static final int PREVIEW_LENGTH = 80;

    /** Длинное голосовое никто не слушает. */
    private static final int MAX_VOICE_LENGTH = 400;

    /** Слова, выдающие ремарку вместо самой речи. */
    private static final List<String> NARRATION_WORDS =
            List.of("голосом", "тоном", "говорит ", "voice", "tone", "says ");

    private final Telegram telegram;
    private final TelegramActions actions;
    private final ChatScreen screen;
    private java.nio.file.Path gallery = java.nio.file.Path.of("data/gallery");
    private java.nio.file.Path voiceDir = java.nio.file.Path.of("data/voice_messages");
    private java.nio.file.Path videoDir = java.nio.file.Path.of("data/tiktok");
    private java.util.function.Supplier<String> speechPrompt =
            () -> "Exactly what you want to say in the voice message";
    private final AntiRepeat antiRepeat;

    private ru.rainedev.raine.vision.TelegramMedia media;
    private final StickerTools stickers;
    private ru.rainedev.raine.speech.VoiceGenerator voice;
    private ru.rainedev.raine.image.ImageGenerator camera;
    private PhotoOutcome photoOutcome = (chatId, ready, message) -> { };

    /**
     * Последний видимый текст, отправленный ею в каждый чат. Нужен, чтобы
     * отличать настоящее исправление опечатки («*допиваю») от пустого, которое
     * лишь повторяет то же слово («*досматриваю» поверх «досматриваю»). Живёт
     * между ходами: модель замечает свою опечатку в истории и «правит» её уже
     * следующим сообщением.
     */
    private final java.util.Map<Long, String> lastSentVisible =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Текущее состояние Юкико: время дня, локация, самочувствие, настроение,
     * что делает прямо сейчас. Используется для фото (чтобы селфи отражало
     * реальное состояние — маска при болезни, пижама вечером, рюкзак в
     * колледже) и для самоконтроля — она всегда знает, где она и что с ней.
     */
    private java.util.function.Supplier<String> currentSituation = () -> "";

    /**
     * Неотвеченные сообщения: кому она написала и не получила ответа.
     * Нужно, чтобы она возвращалась к ним сама, как живой человек.
     */
    private ru.rainedev.raine.memory.PendingReplies pendingReplies;

    /**
     * Отложенные ответы: она пообещала написать позже («щас», «потом»)
     * и реально должна вернуться. Без этого «щас» = пустая фраза.
     */
    private ru.rainedev.raine.memory.DeferredReplies deferredReplies;

    /** Коробка мемов: то, что она сама решила сохранить и кинуть потом. */
    private MemeLibrary memeBox;

    /** Отношения с людьми: близость, обиды, её собственные заметки. */
    private ru.rainedev.raine.memory.Relationships relationships;

    /** Сообщает, чем закончилась фоновая съёмка. */
    @FunctionalInterface
    public interface PhotoOutcome {
        void report(long chatId, boolean ready, String message);
    }
    private final TypingRhythm rhythm;
    private final java.util.random.RandomGenerator random = new java.util.Random();

    /**
     * Вероятность одной опечатки на сообщение. Живая переписка полна промахов
     * по клавишам и переставленных букв: одна опечатка на десять сообщений —
     * как у человека, который печатает с телефона на ходу.
     */
    private static final double TYPO_PROBABILITY = 0.10;

    /** Насколько часто она сама замечает свою опечатку. */
    private static final double TYPO_NOTICED = 0.7;

    /** Насколько часто напоминать, что можно не только писать текстом. */
    private static final double LIVELIER_REMINDER = 0.02;

    private final ru.rainedev.raine.telegram.Lockdown lockdown;

    /** Повадки из настроек: объём истории, стикеры, напоминания. */
    private ru.rainedev.raine.config.Config.Behaviour behaviour =
            new ru.rainedev.raine.config.Config.Behaviour(2000, false, true, false, true, LIVELIER_REMINDER);

    public void behaviour(ru.rainedev.raine.config.Config.Behaviour behaviour) {
        this.behaviour = behaviour;
    }

    /**
     * Чаты, открытые за этот ход. Для Telegram «открытый чат» — это состояние:
     * пока он открыт, туда идут обновления и отметки прочтения. Оставлять их
     * открытыми навсегда — значит выглядеть человеком, у которого раскрыты
     * сразу все переписки.
     */
    private final java.util.Set<Long> opened = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Map<Long, int[]> consecutive = new java.util.concurrent.ConcurrentHashMap<>();

    /** Called on the brain thread, including before writing conversation memory. */
    public synchronized void reconcileConfirmed() {
        if (telegram == null) return;
        for (TdApi.Message message : telegram.delivery().drainConfirmed()) {
            confirmed(message);
        }
    }

    private final java.util.Map<Long, Long> lastIncoming = new java.util.HashMap<>();
    private final java.util.Map<Long, Long> lastConfirmed = new java.util.HashMap<>();

    public synchronized void observeIncoming(TdApi.Message message) {
        if (message.isOutgoing) return;
        lastIncoming.merge(message.chatId, message.id, Math::max);
        if (message.id < lastConfirmed.getOrDefault(message.chatId, Long.MIN_VALUE)) return;
        if (pendingReplies != null) pendingReplies.markAnswered(message.chatId);
        if (deferredReplies != null) deferredReplies.cancelIfAnswered(message.chatId);
    }

    private void confirmed(TdApi.Message message) {
        if (message.id <= lastConfirmed.getOrDefault(message.chatId, Long.MIN_VALUE)) return;
        lastConfirmed.put(message.chatId, message.id);
        if (!(message.content instanceof TdApi.MessageText text) || text.text == null) return;
        String visible = text.text.text;
        lastSentVisible.put(message.chatId, visible);
        // A delayed success update must not resurrect a thread already answered.
        if (message.id <= lastIncoming.getOrDefault(message.chatId, Long.MIN_VALUE)) return;
        if (pendingReplies != null) pendingReplies.markSent(message.chatId, "chat " + message.chatId, visible);
        if (deferredReplies != null) deferredReplies.checkLastSent(message.chatId, "chat " + message.chatId, visible);
    }

    /** Ход закончен: выходим из всех чатов, куда заходили. */
    public void closeOpened() {
        reconcileConfirmed();
        consecutive.clear();
        for (Long chatId : opened) {
            try {
                actions.stopTyping(chatId);
                actions.closeChat(chatId);
            } catch (RuntimeException e) {
                log.debug("Чат {} не закрылся: {}", chatId, e.getMessage());
            }
        }
        opened.clear();
    }

    /** Records reply stats for the dashboard. */
    private void recordReplyStats(long chatId, boolean isGroup, boolean isOwner) {
        WebUi.STATS.recordReply(chatId, isGroup, isOwner);
    }

    /** Records token usage stats for the dashboard. */
    private void recordTokenStats(String model, int tokens) {
        WebUi.STATS.recordTokens(model, tokens);
    }

    /** Determines if a chat is a group/channel vs private. */
    private boolean isGroupChat(long chatId) {
        try {
            TdApi.Chat chat = telegram.chatCached(chatId);
            return chat.type instanceof TdApi.ChatTypeBasicGroup
                    || chat.type instanceof TdApi.ChatTypeSupergroup;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Сейчас она пишет по своему порыву, а не отвечает на уведомление. */
    private java.util.function.BooleanSupplier actingOnImpulse = () -> false;

    private NotificationLoop loop;
    private WebSearch webSearch;

    public TelegramTools(Telegram telegram, TelegramActions actions, ChatScreen screen,
                         ru.rainedev.raine.telegram.Lockdown lockdown, AntiRepeat antiRepeat,
                         NotificationLoop loop, WebSearch webSearch) {
        this.telegram = telegram;
        this.actions = actions;
        this.screen = screen;
        this.lockdown = lockdown;
        this.antiRepeat = antiRepeat;
        this.loop = loop;
        this.webSearch = webSearch;
        this.rhythm = new TypingRhythm(random);
        this.stickers = new StickerTools(telegram, actions);
        this.stickers.allowedTo(chatId -> allowed(telegram.chatCached(chatId)));
    }

    public void webSearch(WebSearch webSearch) {
        this.webSearch = webSearch;
    }

    public StickerTools stickers() {
        return stickers;
    }

    public void actingOnImpulse(java.util.function.BooleanSupplier flag) {
        this.actingOnImpulse = flag;
    }

    /** Съёмка подключается отдельно: без ключа генерации инструмента просто не будет. */
    public void camera(ru.rainedev.raine.image.ImageGenerator camera, java.nio.file.Path gallery,
                       PhotoOutcome outcome) {
        this.camera = camera;
        this.gallery = gallery;
        this.photoOutcome = outcome;
    }

    /**
     * Поставщик текущего состояния: время, локация, самочувствие, настроение.
     * Используется при генерации фото и в других местах, где ей надо
     * понимать, где она и что с ней прямо сейчас.
     */
    public void currentSituation(java.util.function.Supplier<String> situation) {
        this.currentSituation = situation;
    }

    public void pendingReplies(ru.rainedev.raine.memory.PendingReplies pendingReplies) {
        this.pendingReplies = pendingReplies;
    }

    public ru.rainedev.raine.memory.PendingReplies pendingReplies() {
        return pendingReplies;
    }

    public void deferredReplies(ru.rainedev.raine.memory.DeferredReplies deferredReplies) {
        this.deferredReplies = deferredReplies;
    }

    public void memeBox(MemeLibrary memeBox) {
        this.memeBox = memeBox;
    }

    public MemeLibrary memeBox() {
        return memeBox;
    }

    public void relationships(ru.rainedev.raine.memory.Relationships relationships) {
        this.relationships = relationships;
    }

    /** Скорость печати зависит от состояния: сонная печатает медленнее. */
    public void rhythmSpeed(java.util.function.DoubleSupplier factor) {
        this.rhythm.speedFactor(factor);
    }

    public ru.rainedev.raine.memory.Relationships relationships() {
        return relationships;
    }

    /**
     * Сохранить мем из сообщения в свою коробку, чтобы кинуть потом.
     * Что считать смешным — решает она, код не отбирает.
     */
    public Tool saveMeme() {
        return Tool.named("save_meme")
                .describedAs("Saves a photo, gif or sticker from a message into your meme box so you can "
                        + "send it to someone later. Real people collect memes and throw them at friends "
                        + "days later. Use it when something is genuinely funny, not for everything.")
                .requiredInteger("message_id", "Id of the message with the picture/gif/sticker to save")
                .optionalString("chat_id", "Optional. Chat the message is in. Defaults to the open chat.")
                .build(arguments -> {
                    if (memeBox == null) {
                        return "Meme box is not available.";
                    }
                    long messageId = ru.rainedev.raine.core.Numbers.longAt(arguments, "message_id", 0);
                    long where = ru.rainedev.raine.core.Numbers.longAt(arguments, "chat_id", 0);
                    if (messageId == 0) {
                        return "Provide the message_id of the meme you want to keep.";
                    }
                    String name = memeBox.save(where, messageId);
                    if (name.isEmpty()) {
                        return "Couldn't save that — it has no picture, gif or sticker in it.";
                    }
                    return "Saved to your meme box as %s. Send it later with #send_telegram_message "
                            .formatted(name) + "using photo_filename.";
                });
    }

    /** Коробка мемов — что у неё вообще есть кинуть. */
    public Tool memeList() {
        return Tool.simple("meme_list",
                "Shows your meme box: pictures and gifs you saved earlier with a short description "
                        + "of each. Use it when you want to send someone a meme that fits the moment.",
                arguments -> memeBox == null ? "Meme box is not available." : memeBox.list());
    }

    /** Опрос в канале или группе. */
    public Tool createPoll(long chatId) {
        return Tool.named("create_poll")
                .describedAs("Creates a poll in this chat. People post polls in channels and groups "
                        + "all the time - it's a normal way to ask something or just stir up a chat. "
                        + "Options should be short and in your own words, not a formal survey.")
                .requiredString("question", "The poll question, short and casual")
                .requiredString("options", "Answer options separated by | (pipe). Two to six options.")
                .build(arguments -> {
                    String question = arguments.path("question").asText("").strip();
                    String raw = arguments.path("options").asText("").strip();
                    if (question.isEmpty() || raw.isEmpty()) {
                        return "Provide both question and options.";
                    }
                    List<String> options = new java.util.ArrayList<>();
                    for (String part : raw.split("\\|")) {
                        String option = part.strip();
                        if (!option.isEmpty()) {
                            options.add(option);
                        }
                    }
                    if (options.size() < 2) {
                        return "A poll needs at least two options, separated by |";
                    }
                    if (options.size() > 6) {
                        options = options.subList(0, 6);
                    }
                    try (TypingIndicator ignored = TypingIndicator.typing(actions, chatId)) {
                        actions.sendPoll(chatId, question, options);
                    }
                    log.info("Опрос создан в чате {}: {}", chatId, question);
                    return "Poll created.";
                });
    }

    /** Чужие истории — что она может посмотреть и на что ответить. */
    public Tool storyList() {
        return Tool.simple("story_list",
                "Shows whose stories are up right now (chat, title, story id). "
                        + "Watching stories and replying to them is a normal part of Telegram life.",
                arguments -> {
                    List<ru.rainedev.raine.telegram.Telegram.StoryRef> stories;
                    try {
                        stories = telegram.activeStories(20, 3);
                    } catch (RuntimeException e) {
                        return "Couldn't load stories: " + e.getMessage();
                    }
                    if (stories.isEmpty()) {
                        return "Nobody has active stories right now.";
                    }
                    StringBuilder out = new StringBuilder("<active_stories>\n");
                    for (var s : stories) {
                        out.append("<story chat_id=\"").append(s.chatId())
                                .append("\" title=\"").append(s.title())
                                .append("\" story_id=\"").append(s.storyId()).append("\" />\n");
                    }
                    return out.append("</active_stories>\nReply to one with #reply_to_story.").toString();
                });
    }

    /** Ответ на чужую историю — уходит автору в личку. */
    public Tool replyToStory() {
        return Tool.named("reply_to_story")
                .describedAs("Replies to someone's story. The reply lands in your DM with that person, "
                        + "like a normal story reply. Keep it short and natural - that's how people "
                        + "react to stories.")
                .requiredInteger("chat_id", "chat_id of the story author (from #story_list)")
                .requiredInteger("story_id", "story_id from #story_list")
                .requiredString("text", "Your reply - short, casual, like a real reaction")
                .build(arguments -> {
                    long chatId = ru.rainedev.raine.core.Numbers.longAt(arguments, "chat_id", 0);
                    int storyId = (int) ru.rainedev.raine.core.Numbers.longAt(arguments, "story_id", 0);
                    String text = arguments.path("text").asText("").strip();
                    if (chatId == 0 || storyId == 0 || text.isEmpty()) {
                        return "Provide chat_id, story_id and text.";
                    }
                    if (!allowed(telegram.chatCached(chatId))) {
                        return "You can't write to that chat.";
                    }
                    actions.replyToStory(chatId, storyId, text);
                    log.info("Ответ на историю {} в чате {}", storyId, chatId);
                    return "Story reply sent.";
                });
    }

    /**
     * Голос в опросе. Люди голосуют в чужих опросах постоянно — особенно
     * в рофловых, где варианты нарочно абсурдные. Она голосует по-своему:
     * за то, что ей реально ближе или что смешнее, а не за «правильный» ответ.
     */
    public Tool votePoll(long chatId) {
        return Tool.named("vote_poll")
                .describedAs("Votes in a poll you can see in this chat. You vote like a real person: "
                        + "pick what you actually prefer, or what's funnier, not what's 'correct'. "
                        + "For joke polls you can vote for the most absurd option on purpose. "
                        + "You don't have to vote in every poll - skip the boring ones.")
                .requiredInteger("message_id", "message_id of the poll")
                .requiredString("option", "Either the option number (0, 1, 2...) as shown in the poll, "
                        + "or the exact option text")
                .build(arguments -> {
                    long messageId = ru.rainedev.raine.core.Numbers.longAt(arguments, "message_id", 0);
                    String raw = arguments.path("option").asText("").strip();
                    if (messageId == 0 || raw.isEmpty()) {
                        return "Provide the poll's message_id and which option to vote for.";
                    }
                    TdApi.Message message = telegram.message(chatId, messageId).orElse(null);
                    if (!(message != null && message.content instanceof TdApi.MessagePoll pollMessage)) {
                        return "That message is not a poll.";
                    }
                    TdApi.Poll poll = pollMessage.poll;
                    if (poll.isClosed) {
                        return "That poll is already closed.";
                    }
                    // вариант можно назвать номером или текстом — люди пишут и так и так
                    int optionId = -1;
                    try {
                        optionId = Integer.parseInt(raw);
                    } catch (NumberFormatException notANumber) {
                        String needle = raw.toLowerCase();
                        for (int i = 0; i < poll.options.length; i++) {
                            if (poll.options[i].text.text.toLowerCase().contains(needle)) {
                                optionId = i;
                                break;
                            }
                        }
                    }
                    if (optionId < 0 || optionId >= poll.options.length) {
                        return "There is no such option. Valid numbers are 0.." + (poll.options.length - 1)
                                + ". Check the poll text in the chat history.";
                    }
                    try {
                        actions.votePoll(chatId, messageId, optionId);
                    } catch (RuntimeException e) {
                        return "Couldn't vote: " + e.getMessage();
                    }
                    log.info("Голос в опросе {} за вариант {}: {}", messageId, optionId,
                            poll.options[optionId].text.text);
                    return "You voted for: " + poll.options[optionId].text.text;
                });
    }

    /**
     * Заметка о человеке. Она сама решает, что запомнить и как к нему
     * относиться: код не подбирает слова и не оценивает людей.
     */    public Tool noteAboutPerson() {
        return Tool.named("note_about_person")
                .describedAs("Write down something about a person - an inside joke, a detail you want "
                        + "to remember, how they made you feel, AND their gender once you figure it out. "
                        + "This is YOUR memory of them, in your own words. Use it when something worth "
                        + "remembering happens, not after every message.")
                .requiredInteger("user_id", "user_id of the person (from the message sender info)")
                .requiredString("note", "What you want to remember about them, in your own words")
                .optionalString("feeling", "Optional: warm, cold or neutral - how you feel about them now")
                .optionalString("gender",
                        "Their gender: 'female', 'male', or leave empty if you don't know yet. "
                                + "Decide from their own words first ('я пошла'/'я пошёл', 'мы с девчонками'), "
                                + "and if that's not enough you can look at their avatar with #get_chat_photo "
                                + "(anime pics, memes and logos don't count). Names like Саша/Женя/Никита "
                                + "and nicknames like @dark_lord666 are not evidence. Update it when you "
                                + "learn more; it's fine to be wrong at first.")
                .build(arguments -> {
                    if (relationships == null) {
                        return "Not available.";
                    }
                    long userId = ru.rainedev.raine.core.Numbers.longAt(arguments, "user_id", 0);
                    String note = arguments.path("note").asText("").strip();
                    String feeling = arguments.path("feeling").asText("neutral").strip();
                    String gender = arguments.path("gender").asText("").strip();
                    if (userId == 0 || note.isEmpty()) {
                        return "Provide user_id and note.";
                    }
                    String name = telegram.nameOf(userId);
                    relationships.note(userId, name, note, feeling, gender);
                    return "Noted" + (gender.isEmpty() ? "." : " (gender: " + gender + ").");
                });
    }

    /**
     * Снимок. Готовый файл кладётся в галерею, а отправляется отдельно —
     * так она может посмотреть на результат и переснять, если не понравился.
     * <p>
     * Контекст (время, локация, состояние из рабочей памяти) подмешивается
     * к описанию автоматически: модель генератора получает указание учесть
     * текущее состояние — и фото отражает реальность (маска при болезни,
     * пижама ночью, рюкзак в колледже, дождь за окном).
     */
    public Tool takePhoto(long chatId) {
        return Tool.named("take_photo")
                .describedAs("Takes a photo — a selfie, your surroundings, an object, a place, another person, "
                        + "anything. Returns a filename you can then send with send_telegram_message. Describe the "
                        + "vibe, not a complex composition. Your current situation (time, location, physical and "
                        + "emotional state, and your outfit of the day) is injected automatically — the photo will "
                        + "match what's really happening with you right now and keep the same clothes you're "
                        + "wearing today. Not every photo has to be a selfie: you can shoot the view out of the "
                        + "window, your notebook, your desk, your room, your coffee, the street, or friends you're "
                        + "with — in those cases you don't have to be in the frame. You CAN put friends/classmates "
                        + "in the frame: just describe their appearance and what they're doing. For selfies, use "
                        + "natural trendy poses: V-sign, peace sign, covering face with hair/hand, looking away "
                        + "with a smirk, finger on lips \"shh\", hiding behind a cup/phone/book, fake sleep, "
                        + "pretending to cry, tongue out, bunny ears behind friend's head, mirror selfie with "
                        + "phone visible, peace sign over one eye, heart with fingers, covering mouth when "
                        + "laughing, messy hair \"just woke up\", hood up, glasses pushed up, etc. The camera "
                        + "will ALSO receive your current situation (time, location, physical and emotional state, "
                        + "and your outfit of the day) and reflect it in the image. If you're sick — the photo "
                        + "shows a mask or tired face. If it's 11am on a weekday — background is college. If "
                        + "it's raining — window shows rain. Selfie example: \"Raine makes a playful selfie, "
                        + "V-sign, covering face with hair\". Photo of something else: \"the rainy view out of "
                        + "Raine's window\" or \"Raine's open notebook on the desk\". To put someone else in "
                        + "the frame, name them and describe their appearance: \"Selfie of Raine with her "
                        + "classmate: anime young female, gold eyes, white hair, white dress, both making "
                        + "peace signs\".")
                .requiredString("photo_desc", "What the photo should show. If it's a photo of yourself, refer to "
                        + "yourself by name. Avoid complex composition — set the vibe instead. "
                        + "The camera will ALSO receive your current situation (time of day, where you are, "
                        + "how you feel, what you're doing, and your outfit of the day) and reflect it in the image. "
                        + "If you're sick — mention it or don't fight it, the photo will show a mask or tired face. "
                        + "If it's 11am on a weekday — the background should be college, not your bedroom. "
                        + "If it's raining — the window behind you shows rain. "
                        + "Photo of yourself example: \"Raine makes a playful selfie, peace sign, covering face with hair\". "
                        + "Photo of something else example: \"the rainy view out of Raine's window\" or "
                        + "\"Raine's open notebook on the desk\". To put someone else in the frame, name them "
                        + "and describe their appearance: \"Selfie of Raine with her sister: anime young female, "
                        + "gold eyes, white hair, white dress, both making peace signs\".")
                .build(arguments -> {
                    String wish = arguments.path("photo_desc").asText("").strip();
                    if (wish.isEmpty()) {
                        throw new LowQualityException("Опиши, что должно быть на снимке.");
                    }
                    if (camera.isBusy()) {
                        return "You are already taking a photo. Wait until it is ready — you will be told. "
                                + "Do not start another one.";
                    }

                    // подмешиваем текущее состояние: модель промпт-инженера
                    // получает указание учитывать его при составлении SD-промпта
                    String situation = currentSituation.get();
                    String enrichedWish = situation.isEmpty()
                            ? wish
                            : wish + "\n\n[CURRENT SITUATION — you MUST reflect this in the image]:\n" + situation;

                    boolean started = camera.takeInBackground(enrichedWish,
                            photo -> photoOutcome.report(chatId, true, photo.getFileName().toString()),
                            reason -> photoOutcome.report(chatId, false, reason));
                    if (!started) {
                        return "You are already taking a photo. Wait until it is ready.";
                    }

                    actions.uploadingPhoto(chatId);
                    log.info("Съёмка начата: {}", wish);
                    // управление возвращается сразу: очередь бывает занята десятками
                    // минут, и ждать её, замерев посреди разговора, нельзя
                    return "You started taking a photo. It takes a while — carry on talking, "
                            + "you will be notified when it is ready. Do not call take_photo again meanwhile.";
                });
    }

    /**
     * Поиск в вебе — чтобы найти референсы поз, идеи для селфи, мемы, или просто
     * что-то узнать не выходя из чата. Работает через настоящий браузер.
     */
    public Tool webSearch() {
        if (webSearch == null) {
            return Tool.named("web_search")
                    .describedAs("Web search is not configured.")
                    .requiredString("query", "What to search for")
                    .build(args -> "Web search is not available (provider not configured).");
        }
        return Tool.named("web_search")
                .describedAs("Search the web for anything — pose references for selfies, meme ideas, "
                        + "trendy poses, outfit ideas, or just to look something up. "
                        + "Use it when you want to find something: \"cute selfie poses for girls\", "
                        + "\"V-sign pose anime girl\", \"how to hide face with hair selfie\", "
                        + "\"trendy anime girl selfie poses 2024\", \"meme templates\". "
                        + "Returns results with links you can open with #browser_open.")
                .requiredString("query", "What to search for")
                .build(args -> {
                    String query = args.path("query").asText("").strip();
                    if (query.isBlank()) {
                        return "You didn't say what to search for.";
                    }
                    return webSearch.search(query);
                });
    }
                      /** Голос подключается отдельно: без ключа синтеза инструмента просто не будет. */
    public void voice(ru.rainedev.raine.speech.VoiceGenerator voice, java.nio.file.Path voiceDir,
                      java.util.function.Supplier<String> speechPrompt) {
        this.voice = voice;
        this.voiceDir = voiceDir;
        this.speechPrompt = speechPrompt;
    }

    /** Длительность нужна Telegram, иначе полоска голосового показывает ноль. */
    private static int secondsOf(java.nio.file.Path ogg) {
        try {
            // 24 кбит/с — тот битрейт, с которым мы кодируем
            return Math.max(1, (int) (java.nio.file.Files.size(ogg) * 8 / 24_000));
        } catch (java.io.IOException e) {
            return 1;
        }
    }

    /**
     * Голосовое сообщение. В описании инструмента важно подчеркнуть, что текст —
     * это ровно то, что будет произнесено: иначе модель пишет «Raine говорит
     * игриво...», и это зачитывается вслух.
     */
    public Tool recordAudio(long chatId) {
        return Tool.named("record_audio")
                .describedAs("Records a voice message and stores it. The text you provide is exactly what will be "
                        + "said out loud — no narration, no third person, no stage directions. Returns a filename; "
                        + "send it with send_telegram_message using audio_filename.")
                // описание параметра берётся из промпта: это часть характера,
                // а не техническая подпись — правится без пересборки
                .requiredString("audio_desc", speechPrompt.get())
                .build(arguments -> {
                    String text = arguments.path("audio_desc").asText("").strip();
                    if (text.isEmpty()) {
                        throw new LowQualityException("Нечего произносить — напиши, что сказать голосом.");
                    }
                    if (text.length() > MAX_VOICE_LENGTH) {
                        throw new LowQualityException(
                                "Слишком длинно для голосового (%d символов). Скажи короче.".formatted(text.length()));
                    }
                    // ремарка вроде «говорит игривым тоном» будет зачитана вслух
                    // ровно так, как написана
                    String lower = text.toLowerCase();
                    for (String narration : NARRATION_WORDS) {
                        if (lower.contains(narration)) {
                            throw new LowQualityException(
                                    "Не описывай, как ты это говоришь — напиши только сами слова. Вместо "
                                    + "«говорит игриво: привет» просто «привет».");
                        }
                    }
                    actions.recordingVoice(chatId);
                    var recorded = voice.speak(text);
                    // не отправляем сразу: запись сохраняется, а отправку она делает
                    // отдельным шагом — так же, как со снимком
                    log.info("Голосовое записано: {}", recorded.file().getFileName());
                    return "Voice message recorded.\n\nFilename: %s\n\nSend it with send_telegram_message "
                            .formatted(recorded.file().getFileName()) + "using audio_filename.";
                });
    }

    /** Зрение появляется позже клиента, поэтому подключается отдельно. */
    public void media(ru.rainedev.raine.vision.TelegramMedia media) {
        this.media = media;
        stickers.media(media);
    }

    /** Куда складывать скачанные ролики (TikTok) — там же ищет их отправка и истории. */
    public void videoDir(java.nio.file.Path dir) {
        if (dir != null) {
            this.videoDir = dir;
        }
    }

    private static boolean isVideoFile(String filename) {
        String lower = filename.toLowerCase();
        return lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".webm")
                || lower.endsWith(".mkv") || lower.endsWith(".avi");
    }

    /**
     * Ищет файл по имени в галерее, в папке роликов (TikTok) и в коробке мемов:
     * она называет только имя, а где он лежит — забота кода.
     */
    private java.nio.file.Path resolveMedia(String filename) {
        java.nio.file.Path file = gallery.resolve(filename);
        if (java.nio.file.Files.exists(file)) {
            return file;
        }
        if (videoDir != null) {
            java.nio.file.Path video = videoDir.resolve(filename);
            if (java.nio.file.Files.exists(video)) {
                return video;
            }
        }
        if (memeBox != null) {
            java.nio.file.Path meme = memeBox.resolve(filename);
            if (meme != null && java.nio.file.Files.exists(meme)) {
                return meme;
            }
        }
        return null;
    }

    /**
     * История в Telegram. Публикуется в её профиль, а не в чат, поэтому
     * доступна из любого открытого чата. Фото или короткий ролик должны быть
     * готовы — сняты через take_photo, лежать в галерее или быть скачаны из TikTok.
     */
    public Tool postStoryTool() {
        return Tool.named("post_story")
                .describedAs("Posts a photo OR a short video as a Telegram Story on your profile. Use it "
                        + "to share moments of your day: a selfie at college, a coffee, a walk, a mood — "
                        + "or a beautiful edit you found (e.g. a clip you downloaded from TikTok). "
                        + "Requires a photo or video file. The caption should be a short thought, a mood, "
                        + "a single phrase — not a long story.")
                .requiredString("photo_filename", "Filename of the photo or video: one returned by "
                        + "take_photo, a file in the gallery, or a downloaded clip (e.g. a tiktok_*.mp4).")
                .optionalString("caption", "Short caption for the story: a thought, a phrase, "
                        + "a mood. Keep it brief — one or two lines at most.")
                .build(arguments -> {
                    String filename = arguments.path("photo_filename").asText("").strip();
                    if (filename.isEmpty()) {
                        return "Provide a photo_filename: take a photo with #take_photo, pick one from "
                                + "the gallery, or download a clip with #tiktok_download_current.";
                    }
                    if (!isPlainFilename(filename)) {
                        return "Filename must be a plain name, without slashes or dots leading elsewhere.";
                    }
                    java.nio.file.Path media = resolveMedia(filename);
                    if (media == null) {
                        return "There is no such file. Take a photo with #take_photo, or download a clip "
                                + "with #tiktok_download_current first.";
                    }
                    String caption = arguments.path("caption").asText("").strip();
                    boolean video = isVideoFile(filename);
                    try {
                        long storyId = video
                                ? telegram.postVideoStory(media, caption)
                                : telegram.postStory(media, caption);
                        log.info("История опубликована: {} ({} id {})", filename,
                                video ? "видео" : "фото", storyId);
                        return video ? "Video story posted." : "Story posted.";
                    } catch (RuntimeException e) {
                        log.warn("Не удалось опубликовать историю: {}", e.getMessage());
                        return "Couldn't post the story: " + e.getMessage();
                    }
                });
    }

    /** Аватарка — по ней понятно, как выглядит собеседник. */
    public Tool chatPhoto(long chatId) {
        return Tool.simple("get_chat_photo", "Looks at the profile photo of this chat.", arguments -> {
            if (media == null) {
                return "You can't see photos right now.";
            }
            return telegram.chatPhoto(chatId)
                    .map(photo -> media.describeAvatar(photo))
                    .filter(described -> !described.isBlank())
                    // без этой оговорки она заговорит о фотографии так, будто её
                    // только что прислали, а это аватарка
                    .map(described -> described + "\nThis is their profile photo. If you mention it, make clear "
                            + "you are talking about their avatar.")
                    .orElse("This chat has no profile photo.");
        });
    }

    /**
     * Открывает чат: показывает переписку и подменяет набор инструментов на те,
     * что доступны внутри этого чата.
     *
     * @param toolbox набор, который будет дополнен после открытия
     */
    public Tool open(long chatId) {
        // в описании — имя чата: по нему видно, кто написал, ещё до открытия
        String title = "";
        try {
            title = telegram.chatCached(chatId).title;
        } catch (RuntimeException e) {
            log.debug("Название чата {} недоступно", chatId);
        }
        return Tool.named("open")
                .describedAs(title.isEmpty()
                        ? "Opens the chat from the notification and shows recent messages."
                        : "Open \"" + title + "\" chat. Use this if you'd like to reply or see messages.")
                .buildContextual((arguments, addTool) -> {
            telegram.delivery().requireReady();
            TdApi.Chat chat = telegram.chat(chatId);
            if (!allowed(chat)) {
                // проверка и здесь, а не только на входящих: найдя человека поиском,
                // она открыла бы чат по идентификатору и написала бы в обход запрета
                log.info("Чат {} вне круга общения — не открываю", chatId);
                return "No such chat.";
            }
            actions.setOnline(true);   // зашла в Telegram — это видно собеседнику
            actions.openChat(chatId);
            opened.add(chatId);
            if (loop != null) {
                loop.setCurrentChatId(chatId);
            }

            List<TdApi.Message> history = recentEnough(telegram.history(chatId, HISTORY_DEPTH));
            var view = new MessageFormatter.ChatView(telegram.myId(), chat.lastReadInboxMessageId);
            ChatScreen.Screen rendered = screen.render(chat, history, view);

            actions.markRead(chatId, history.stream().mapToLong(message -> message.id).toArray());

            // свои прошлые реплики — чтобы не повторяться и после перезапуска
            history.stream()
                    .filter(message -> message.senderId instanceof TdApi.MessageSenderUser sender
                            && sender.userId == telegram.myId())
                    .filter(message -> message.content instanceof TdApi.MessageText)
                    .forEach(message -> antiRepeat.remember(((TdApi.MessageText) message.content).text.text));

            // пустой чат — это человек, с которым она ещё не говорила ни разу.
            // Заговаривать с незнакомыми первой можно разрешить настройкой,
            // но по умолчанию это выключено: слишком легко написать не туда
            boolean stranger = history.isEmpty() && rendered.kind() == ChatKind.DM
                    && !lockdown.isOwner(chatId);
            if (stranger && !behaviour.writeToNewPeople()) {
                log.info("Чат {} пуст, а писать незнакомым не разрешено", chatId);
                return "This chat is empty — you have never talked to this person. "
                        + "You cannot write to them. Go back to a chat you already have.";
            }

            // в чужом канале отвечать нечем — инструмента отправки там просто
            // нет, но реагировать и пересылать можно. В своём собственном
            // (владелец или администратор с правом публикации) постинг доступен
            boolean ownChannel = rendered.kind() == ChatKind.CHANNEL && telegram.canPostMessages(chatId);
            addTool.accept(searchMessages(chatId, view));
            addTool.accept(messagesAround(chatId, view));
            if (rendered.kind() != ChatKind.CHANNEL || ownChannel) {
                addTool.accept(send(chatId));
                if (voice != null && voice.isAvailable()) {
                    addTool.accept(recordAudio(chatId));
                }
                if (camera != null && camera.isAvailable()) {
                    addTool.accept(takePhoto(chatId));
                }
                if (behaviour.stickers()) {
                    addTool.accept(stickers.send(chatId));
                }
                addTool.accept(editMessage(chatId));
                addTool.accept(deleteMessage(chatId));
            }
            // истории доступны из любого открытого чата: они публикуются
            // в её профиль, а не в чат, поэтому привязки к чату нет
            if (telegram.canPostStories(chatId) || telegram.isPremium()) {
                addTool.accept(postStoryTool());
            }
            addTool.accept(chatPhoto(chatId));
            addTool.accept(react(chatId));
            // гифки: сохранить чужую и отправить свою — только внутри чата
            addTool.accept(saveGif(chatId));
            addTool.accept(sendGif(chatId));
            if (behaviour.stickers()) {
                addTool.accept(stickers.save());
                addTool.accept(stickers.savePack());
            }
            addTool.accept(forward(chatId));
            addTool.accept(blockChat(chatId));
            // коробка мемов: сохранить из этого чата и кинуть потом
            if (memeBox != null) {
                addTool.accept(saveMeme());
            }
            // опрос — в группах и в своём канале, где постить вообще можно
            if (rendered.kind() != ChatKind.CHANNEL || ownChannel) {
                addTool.accept(createPoll(chatId));
            }
            // голосовать можно везде, где есть опрос — и в чужих каналах тоже
            addTool.accept(votePoll(chatId));
            // комментарий под постом: в чужом канале отвечать нельзя, но
            // оставить комментарий в обсуждении поста можно — как живой подписчик
            if (rendered.kind() == ChatKind.CHANNEL) {
                addTool.accept(commentOnPost(chatId));
            }
            // заметки о людях: код хранит, а что запомнить решает она
            if (relationships != null) {
                addTool.accept(noteAboutPerson());
            }
            if (stranger) {
                // здесь ещё ничего не сказано: без опоры на разговор легко
                // написать что-то невпопад или не тому
                return rendered.text() + "\n\nThis chat is empty! Only proceed if you looked up a @username "
                        + "and it led you here. Write only what you have to say yourself. If you then go back "
                        + "to the original chat, remember you have already sent a message here.";
            }
            // что она знает об этом человеке: близость, обиды, её собственные
            // заметки. Код только показывает факты — как себя вести, решает она
            if (relationships != null && rendered.kind() == ChatKind.DM) {
                long personId = chat.type instanceof TdApi.ChatTypePrivate priv ? priv.userId : 0;
                if (personId != 0) {
                    String about = relationships.contextFor(personId);
                    if (!about.isEmpty()) {
                        return rendered.text() + "\n" + about;
                    }
                }
            }
            return rendered.text();
        });
    }

    /**
     * Отправка сообщения. Живой разговор состоит из коротких реплик,
     * поэтому простыня отклоняется с просьбой разбить её на части.
     * <p>
     * Цитирование по умолчанию не ставится: в переписке отвечают обычным
     * сообщением, а цитата нужна, только когда разговор ушёл вперёд и надо
     * показать, к чему относится реплика.
     */
    public Tool send(long chatId) {
        // Keep the counter when the model reopens the same chat during this turn.
        int[] inARow = consecutive.computeIfAbsent(chatId, ignored -> new int[1]);
        long[] lastQuoted = {0};
        return Tool.named("send_telegram_message")
                .describedAs("Sends one short message to the currently opened chat. "
                        + "One call = one message. Split longer thoughts into several calls.\n\n"
                        + "Premium (custom) emojis: use :custom:1234567890: anywhere in the text — "
                        + "it will be replaced with the actual custom emoji. Get IDs from reactions "
                        + "on messages (shown as 'premium custom:<id>') or from premium reactions "
                        + "a message accepts. Regular emoji still work but look dated — prefer premium.")
                .optionalString("text", "Text of the message. May be left out if you attach a photo or a voice "
                        + "message. Supports :custom:<id>: placeholders for premium emojis.")
                .optionalString("allow_typos", "Optional. Set to \"false\" for a message where a typo would be "
                        + "out of place. Defaults to true.")
                .optionalString("photo_filename", "Optional. Filename returned by take_photo — the photo will be "
                        + "sent with this text as its caption.")
                .optionalString("audio_filename", "Optional. Filename returned by record_audio — the voice message "
                        + "will be sent instead of text.")
                .optionalString("video_filename", "Optional. Filename of a video to send (e.g. a clip you "
                        + "downloaded from TikTok) — the text becomes its caption.")
                .optionalString("reply_to_message_id",
                        "Optional. Quote a specific earlier message by its message_id. Leave empty for a normal "
                                + "reply — quoting every message looks unnatural in a live conversation.")
                .build(arguments -> {
                    String text = arguments.path("text").asText("").replace("\r", "").strip();
                    String photoName = arguments.path("photo_filename").asText("").strip();
                    String audioName = arguments.path("audio_filename").asText("").strip();
                    String videoName = arguments.path("video_filename").asText("").strip();
                    if (actions instanceof TelegramActions.ReadOnly) return "Read-only mode: nothing was sent.";
                    List<String> confirmedParts = new java.util.ArrayList<>();
                    telegram.delivery().requireReady();

                    // «печатает…» держится всё время работы инструмента: пока считается
                    // вектор и грузится файл, собеседник иначе видит пустоту
                    try (TypingIndicator ignored = TypingIndicator.typing(actions, chatId)) {

                    if (text.isEmpty() && photoName.isEmpty() && audioName.isEmpty() && videoName.isEmpty()) {
                        throw new LowQualityException("Пустое сообщение отправлять незачем — напиши, что хотела.");
                    }
                    int attachments = (photoName.isEmpty() ? 0 : 1) + (audioName.isEmpty() ? 0 : 1)
                            + (videoName.isEmpty() ? 0 : 1);
                    if (attachments > 1) {
                        return "Attach only one thing at a time: a photo, a voice message or a video.";
                    }
                    if (!audioName.isEmpty() && !text.isEmpty()) {
                        // у голосового не бывает подписи: либо голос, либо текст
                        return "A voice message cannot carry text. Send them separately.";
                    }
                    if (inARow[0] >= MAX_IN_A_ROW) {
                        // предупреждения она перестаёт замечать — дальше только отказ
                        return "Too many messages in a row. Stop and let them answer.";
                    }
                    if (text.length() > MAX_MESSAGE_LENGTH) {
                        throw new LowQualityException(
                                "Слишком длинно для переписки (%d символов). Разбей на несколько коротких сообщений."
                                        .formatted(text.length()));
                    }
                    // чем длиннее реплика, тем выше шанс попросить разбить её на части.
                    // мягкий отказ работает лучше жёсткого предела: короткие сообщения
                    // становятся привычкой, а не исключением
                    // придираемся, только если это единственная отправка за шаг:
                    // когда она и так шлёт несколько реплик подряд, придирка ни к чему
                    if (!text.contains("```")
                            && ru.rainedev.raine.core.CurrentStep.countOf("send_telegram_message") <= 1
                            && random.nextDouble() < Math.clamp((text.length() - 50) / 200.0, 0, 1) * SPLIT_NUDGE) {
                        throw new LowQualityException("""
                                Разбей ответ на короткие отдельные сообщения. Например:
                                - ахахаха
                                - ты смешной
                                - научишь так же?""");
                    }
                    Long quotedCheck = quotedMessageId(arguments);
                    if (quotedCheck != null && quotedCheck == lastQuoted[0]) {
                        // на одно и то же сообщение дважды подряд не отвечают
                        quotedCheck = null;
                    } else if (quotedCheck != null) {
                        lastQuoted[0] = quotedCheck;
                    }
                    if (quotedCheck != null && telegram.message(chatId, quotedCheck).isEmpty()) {
                        // сообщение из другого чата: модель путает чаты, а цитата
                        // из чужой переписки — это уже утечка личного
                        log.warn("Попытка процитировать сообщение {} не из этого чата", quotedCheck);
                        return "That message is not from this chat. Quote only messages you see here, "
                                + "or leave reply_to_message_id empty.";
                    }

                    // снимок уходит с первой строкой в подписи, остальные строки —
                    // отдельными сообщениями следом, как обычная реплика
                    if (!photoName.isEmpty()) {
                        if (!isPlainFilename(photoName)) {
                            return "Filename must be a plain name, without slashes or dots leading elsewhere.";
                        }
                        java.nio.file.Path photo = gallery.resolve(photoName);
                        if (!java.nio.file.Files.exists(photo) && memeBox != null) {
                            // не снимок из галереи — может, мем из коробки
                            java.nio.file.Path meme = memeBox.resolve(photoName);
                            if (meme != null) {
                                photo = meme;
                            }
                        }
                        if (!java.nio.file.Files.exists(photo)) {
                            return "There is no such photo. Take one with take_photo first, "
                                    + "or check your meme box with #meme_list.";
                        }
                        List<String> lines = linesOf(text);
                        try (TypingIndicator uploading = TypingIndicator.uploadingPhoto(actions, chatId)) {
                            actions.sendPhoto(chatId, photo, lines.isEmpty() ? "" : lines.getFirst());
                            confirmedParts.add("photo " + photoName);
                        }
                        log.info("Фото отправлено в чат {}: {}", chatId, photoName);
                        for (String rest : lines.subList(Math.min(1, lines.size()), lines.size())) {
                            rhythm.sleepBefore(rest.length(), () -> actions.typing(chatId));
                            long id = actions.sendMessageWithCustomEmojis(chatId, rest.replace("—", "-"), null);
                            recordReplyStats(chatId, isGroupChat(chatId), lockdown.isOwner(chatId));
                            confirmedParts.add("message_id=" + id);
                        }
                        inARow[0]++;
                        return "Photo sent.";
                    }

                    // ролик уходит с подписью из текста: так она кидает друзьям
                    // смешное видео из TikTok или делится эдитом
                    if (!videoName.isEmpty()) {
                        if (!isPlainFilename(videoName)) {
                            return "Filename must be a plain name, without slashes or dots leading elsewhere.";
                        }
                        java.nio.file.Path video = resolveMedia(videoName);
                        if (video == null) {
                            return "There is no such video. Download one with #tiktok_download_current first.";
                        }
                        try (TypingIndicator uploading = TypingIndicator.uploadingVideo(actions, chatId)) {
                            actions.sendVideo(chatId, video, text.replace("—", "-"));
                        }
                        log.info("Видео отправлено в чат {}: {}", chatId, videoName);
                        inARow[0]++;
                        return "Video sent.";
                    }

                    if (!audioName.isEmpty()) {
                        if (!isPlainFilename(audioName)) {
                            return "Filename must be a plain name, without slashes or dots leading elsewhere.";
                        }
                        java.nio.file.Path recorded = voiceDir.resolve(audioName);
                        if (!java.nio.file.Files.exists(recorded)) {
                            return "There is no such voice message. Record one with record_audio first.";
                        }
                        try (TypingIndicator recording = TypingIndicator.recordingVoice(actions, chatId)) {
                            actions.sendVoice(chatId, recorded, secondsOf(recorded));
                        }
                        log.info("Голосовое отправлено в чат {}: {}", chatId, audioName);
                        return "Voice message sent.";
                    }

                    maybeSuggestSomethingLivelier();
                    antiRepeat.check(text);

                    boolean allowTypos = !"false".equalsIgnoreCase(arguments.path("allow_typos").asText("true"));
                    Long quoted = quotedCheck;

                    long lastSent = 0;
                    String lastText = text;
                    // с чем сравнивать «*слово»: что она реально отправила
                    // последней (в прошлый ход) плюс строки этого же вызова
                    StringBuilder visibleSoFar =
                            new StringBuilder(lastSentVisible.getOrDefault(chatId, ""));

                    // переносы строк внутри одной реплики — это отдельные сообщения
                    for (String line : linesOf(text)) {
                        // модели пишут длинное тире, а с клавиатуры набирают дефис
                        String piece = line.replace("—", "-");

                        // пустое исправление («*досматриваю» вместо «*допиваю»)
                        // ничего не исправляет, а лишь повторяет прежнюю опечатку.
                        // опечатки правит рука (fixTypo), не голова, — такое не шлём
                        if (isNoOpCorrection(piece, visibleSoFar.toString())) {
                            log.info("Пустое исправление опечатки пропущено: {}", piece);
                            continue;
                        }

                        Typos.Slip slip = allowTypos
                                ? Typos.maybeAdd(piece, TYPO_PROBABILITY, random)
                                : new Typos.Slip(piece, "");

                        actions.typing(chatId);
                        // иногда «печатает…» появляется и пропадает: начала писать
                        // и передумала. Очень человеческий паттерн, который боты
                        // почти никогда не имитируют
                        if (random.nextDouble() < 0.06) {
                            try {
                                Thread.sleep(java.time.Duration.ofMillis(1500 + random.nextInt(3000)));
                                actions.stopTyping(chatId);
                                Thread.sleep(java.time.Duration.ofMillis(2000 + random.nextInt(4000)));
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
                        rhythm.sleepBefore(slip.text().length(), () -> actions.typing(chatId));
                        // номер, который отдаётся модели, — настоящий: по временному
                        // не поправить и не удалить только что отправленное
                        long sent = actions.sendMessageWithCustomEmojis(chatId, slip.text(), quoted);
                        recordReplyStats(chatId, isGroupChat(chatId), lockdown.isOwner(chatId));
                        confirmedParts.add("message_id=" + sent);
                        quoted = null;   // цитата уместна только у первого сообщения
                        log.info("Отправлено в чат {}: {}", chatId, slip.text());
                        // запоминаем видимый текст: по нему следующий ход отличит
                        // настоящее исправление от пустого
                        visibleSoFar.append(' ').append(slip.text());
                        lastSentVisible.put(chatId, slip.text());

                        if (slip.happened()) {
                            fixTypo(chatId, sent, slip, piece);
                        }
                        // живые люди иногда ставят реакции на свои же сообщения —
                        // это делается на потоке отправки, чтобы не тормозить модель
                        Thread.ofVirtual().name("raine-selfreact").start(
                                () -> maybeReactToOwn(chatId, sent, piece));
                        lastSent = sent;
                        // ей сообщается то, что она хотела сказать, а не то, что вышло
                        // из-под руки: опечатку замечает и исправляет рука, не голова.
                        // Пока в ответе стоял испорченный текст, она видела в нём ошибку
                        // и правила сообщение сама — уже поверх дописанного «*слово»,
                        // и выходило два исправления одного промаха
                        lastText = piece;
                    }
                    // считаем вызовы, а не строки: реплика, разбитая на три части,
                    // остаётся одной репликой
                    inARow[0]++;

                    // запоминаем, что ждём ответ: если собеседник не ответит,
                    // она может вернуться к этому сама через час-два
                    reconcileConfirmed();
                    // отложенный ответ: если она написала «щас» / «потом напишу» /
                    // «на паре», запоминаем чтобы реально вернуться через 20-90 мин.
                    // это то, что отличает живого человека от бота
                    // Deferred promises are also updated only by reconcileConfirmed().

                    // иногда отправляет и сразу удаляет («ой не то»):
                    // живые люди так делают, когда передумали или опечатались
                    if (lastSent != 0 && random.nextDouble() < 0.02) {
                        final long toDelete = lastSent;
                        Thread.ofVirtual().name("raine-oops").start(() -> {
                            try {
                                Thread.sleep(java.time.Duration.ofMillis(800 + random.nextInt(1500)));
                                actions.deleteMessage(chatId, toDelete);
                                log.info("Сообщение удалено сразу после отправки (ой не то)");
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            } catch (RuntimeException e) {
                                log.debug("Не удалось удалить сообщение: {}", e.getMessage());
                            }
                        });
                    }

                    if (lastSent == 0) return "No new message was sent.";
                    return sendResult(inARow[0], lastSent, lastText);
                    } catch (ru.rainedev.raine.telegram.TelegramDelivery.Unavailable e) {
                        return (confirmedParts.isEmpty() ? "" : "Already confirmed: " + String.join(", ", confirmedParts)
                                + ". Do not repeat those parts.\n") + e.getMessage();
                    } finally {
                        reconcileConfirmed();
                    }
                });
    }

    /** Пустые строки внутри реплики отправлять незачем. */
    private static List<String> linesOf(String text) {
        return java.util.Arrays.stream(text.split("\n"))
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .toList();
    }

    /**
     * История чата ограничивается объёмом текста, а не числом сообщений.
     * Тридцать длинных постов из канала — это уже перебор контекста, а тридцать
     * «ок» — наоборот, слишком мелкий кусок разговора.
     *
     * @param history от старых к свежим
     */
    private List<TdApi.Message> recentEnough(List<TdApi.Message> history) {
        int budget = behaviour.chatHistoryLength();
        int taken = 0;
        int from = history.size();
        while (from > 0) {
            taken += MessageFormatter.contentOf(history.get(from - 1).content).length();
            from--;
            if (taken >= budget) {
                break;
            }
        }
        return history.subList(from, history.size());
    }

    /** Реакция — способ откликнуться, не заводя разговор. */
    public Tool react(long chatId) {
        return Tool.named("react_with_emoji")
                .describedAs("Reacts to a message with an emoji — regular or premium. STRONGLY PREFER premium "
                        + "custom reactions: almost nobody uses plain emoji anymore, so a premium reaction is what "
                        + "makes you look like a real person. Pass a premium reaction as 'custom:<id>'. Where to get "
                        + "the id: (a) the reactions already shown on messages in this chat — a premium one appears "
                        + "as 'premium custom:<id>', so copy that id and reuse it (borrowing other people's emoji "
                        + "packs is completely normal), or (b) whatever premium reactions this message accepts. "
                        + "Fall back to a regular emoji (👍, 🔥, ❤, …) ONLY when no premium reaction is available. "
                        + "Regular allowed: "
                        + String.join(" ", Emoji.ALLOWED))
                .requiredInteger("message_id", "Id of the message to react to")
                .requiredString("emoji", "Premium 'custom:<id>' (preferred) or a regular emoji")
                .build(arguments -> {
                    if (actions instanceof TelegramActions.ReadOnly) return "Read-only mode: no reaction was submitted.";
                    telegram.delivery().requireReady();
                    long messageId = Numbers.longAt(arguments, "message_id", 0);
                    String raw = arguments.path("emoji").asText("").strip();
                    if (raw.isEmpty()) {
                        log.info("Реакция не поставлена: эмодзи не разобрано ({})",
                                arguments.path("emoji").asText(""));
                        return "Provide an emoji or custom:<id> to react with.";
                    }

                    if (telegram.message(chatId, messageId).isEmpty()) {
                        log.info("Реакция не поставлена: сообщения {} нет в чате {}", messageId, chatId);
                        return "There is no message with id " + messageId + " in this chat. Look at the "
                                + "message_id values you actually see here and pick one of those.";
                    }

                    // премиум-реакция: custom:<id> ставится отдельным путём
                    if (raw.startsWith("custom:")) {
                        try {
                            actions.react(chatId, messageId, raw);
                        } catch (ru.rainedev.raine.telegram.TelegramDelivery.Unavailable e) {
                            return e.getMessage();
                        } catch (RuntimeException e) {
                            log.info("Премиум-реакция на сообщение {} не прошла: {}", messageId, e.getMessage());
                            return "Couldn't set premium reaction: " + e.getMessage();
                        }
                        log.info("Премиум-реакция {} на сообщение {}", raw, messageId);
                        return "Reacted with premium " + raw;
                    }

                    // обычная эмодзи-реакция
                    String wanted = Emoji.normalize(raw);
                    List<String> available = telegram.availableReactions(chatId, messageId);
                    String exact = available.stream()
                            .filter(candidate -> !candidate.startsWith("custom:")
                                    && Emoji.normalize(candidate).equals(wanted))
                            .findFirst()
                            .orElse(null);

                    if (exact == null && available.isEmpty()) {
                        log.info("Набор реакций для сообщения {} пуст — пробую поставить {} как есть",
                                messageId, wanted);
                        exact = wanted;
                    }
                    if (exact == null) {
                        log.info("Реакция {} недоступна для сообщения {}; здесь можно: {}",
                                wanted, messageId, String.join(" ", available));
                        return "The reaction " + wanted + " isn't available for this message. Allowed here: "
                                + String.join(" ", available) + ". Pick one of those, or just skip reacting.";
                    }

                    try {
                        actions.react(chatId, messageId, exact);
                    } catch (ru.rainedev.raine.telegram.TelegramDelivery.Unavailable e) {
                        return e.getMessage();
                    } catch (RuntimeException e) {
                        log.info("Реакция на сообщение {} не прошла: {}", messageId, e.getMessage());
                        String reason = e.getMessage() == null ? "" : e.getMessage().toUpperCase();
                        if (reason.contains("REACTION")) {
                            return "Reactions are switched off in this chat, so nobody can react here. "
                                    + "Nothing is broken: just skip reacting and carry on.";
                        }
                        return "This particular message did not accept the reaction. Happens with some "
                                + "messages; nothing is broken. Skip it and carry on.";
                    }
                    log.info("Реакция {} на сообщение {}", exact, messageId);
                    return "Reaction " + exact + " added.";
                });
    }

    /** Список чатов — как экран мессенджера: видно, где есть непрочитанное. */
    public Tool chatList() {
        return Tool.simple("get_telegram_chats", "Shows your Telegram chats with unread counts.", arguments -> {
            // от старых к свежим: список разбирается сверху вниз, и наверху
            // должно оказаться то, до чего иначе не дойдут руки
            List<TdApi.Chat> chats = new java.util.ArrayList<>(telegram.chats(CHAT_LIST_DEPTH));
            java.util.Collections.reverse(chats);

            // чат, с которым нельзя заговорить, в списке только мешает
            chats.removeIf(chat -> !allowed(chat));

            if (actingOnImpulse.getAsBoolean()) {
                // весь смысл порыва — вернуться к заглохшим разговорам. Про те,
                // где есть непрочитанное, она и так узнает из уведомлений
                chats.removeIf(chat -> chat.unreadCount > 0);
            }

            StringBuilder out = new StringBuilder(
                    "You are looking at your Telegram main screen. You see these chats:\n<chats>\n");
            for (TdApi.Chat chat : chats) {
                out.append("<chat id=\"").append(chat.id).append("\" title=\"").append(chat.title)
                        .append("\" kind=\"").append(kindOf(chat)).append('"');
                if (chat.unreadCount > 0) {
                    out.append(" unread_count=\"").append(chat.unreadCount).append('"');
                }
                // по одному названию не выбрать, кому написать: превью показывает,
                // о чём там речь и как давно
                String preview = previewOf(chat);
                if (!preview.isEmpty()) {
                    out.append('>').append(preview).append("</chat>\n");
                } else {
                    out.append("/>\n");
                }
            }
            return out.append("</chats>").toString();
        });
    }

    /** Поиск человека или чата — так завязывается разговор с тем, кому ещё не писала. */
    public Tool searchChats() {
        return Tool.named("search_chats")
                .describedAs("Finds a person or chat by name or @username. Searches your contacts, your existing "
                        + "chats and public channels at once. Use it before starting a conversation with someone "
                        + "new — if the chat already exists, continue there instead.")
                .requiredString("query", "Person's name, chat title or @username")
                .build(arguments -> {
                    String query = arguments.path("query").asText("").strip();
                    if (query.isEmpty()) {
                        return "Provide a name or @username to search for.";
                    }
                    Telegram.Found found = telegram.searchChats(query, SEARCH_LIMIT);
                    if (found.isEmpty()) {
                        return "Nothing found for: " + query;
                    }
                    // разделено нарочно: со своими разговор продолжают с того места,
                    // где он встал, а к чужим приходят с нуля — это разные ситуации
                    StringBuilder out = new StringBuilder();
                    appendChats(out, "chats_you_are_already_in", found.mine());
                    appendChats(out, "chats_that_do_not_know_you", found.elsewhere());
                    return out.toString();
                });
    }

    private static void appendChats(StringBuilder out, String tag, List<TdApi.Chat> chats) {
        if (chats.isEmpty()) {
            return;
        }
        out.append('<').append(tag).append(">\n");
        for (TdApi.Chat chat : chats) {
            out.append("<chat id=\"").append(chat.id)
                    .append("\" title=\"").append(chat.title)
                    .append("\" kind=\"").append(kindOf(chat))
                    .append("\"/>\n");
        }
        out.append("</").append(tag).append(">\n");
    }

    /** Тип важен: с человеком можно заговорить, а в канал не напишешь. */
    private static String kindOf(TdApi.Chat chat) {
        return switch (ChatKind.of(chat)) {
            case DM -> "person";
            case GROUP -> "group";
            case CHANNEL -> "channel";
        };
    }

    /** Кто и что написал последним, и когда — как в списке чатов обычного клиента. */
    private String previewOf(TdApi.Chat chat) {
        if (chat.lastMessage == null) {
            return "";
        }
        long senderId = chat.lastMessage.senderId instanceof TdApi.MessageSenderUser user ? user.userId : 0;
        String preview = (telegram.nameOf(senderId) + ": "
                + ru.rainedev.raine.phone.MessageFormatter.contentOf(chat.lastMessage.content)).replace("\n", " ");
        if (preview.length() > PREVIEW_LENGTH) {
            preview = preview.substring(0, 30) + "..." + preview.substring(preview.length() - 30);
        }
        return preview + " (" + ru.rainedev.raine.phone.TimeAgo.of(chat.lastMessage.date) + ")";
    }

    /** Свои контакты — список людей, которых она знает по именам. */
    public Tool contactList() {
        return Tool.simple("get_contacts", "Shows people saved in your contacts.", arguments -> {
            List<TdApi.User> contacts = telegram.contacts(CONTACT_LIMIT);
            if (contacts.isEmpty()) {
                return "Your contact list is empty.";
            }
            StringBuilder out = new StringBuilder("<contacts>\n");
            for (TdApi.User user : contacts) {
                out.append("<contact user_id=\"").append(user.id).append("\" name=\"")
                        .append((user.firstName + " " + user.lastName).strip()).append("\"/>\n");
            }
            return out.append("</contacts>").toString();
        });
    }

    /**
     * Сохранение в контакты. Человек, записанный по имени, потом находится
     * поиском — иначе он теряется среди чатов, как только переписка уходит вниз.
     */
    public Tool saveContact() {
        return Tool.named("save_contact")
                .describedAs("Saves a person to your contacts so you can find them by name later. Use it when "
                        + "you got to know someone and want to keep them.")
                .requiredInteger("user_id", "Id of the person")
                .requiredString("first_name", "How you want to call them in your contacts")
                .optionalString("last_name", "Optional last name")
                .build(arguments -> {
                    long userId = Numbers.longAt(arguments, "user_id", 0);
                    String firstName = arguments.path("first_name").asText("").strip();
                    if (userId == 0 || firstName.isEmpty()) {
                        return "Both user_id and first_name are required.";
                    }
                    actions.saveContact(userId, firstName, arguments.path("last_name").asText("").strip());
                    log.info("Контакт сохранён: {} ({})", firstName, userId);
                    return "Contact saved.";
                });
    }

    /** Открытие чата по идентификатору — так она пишет первой. */
    public Tool openById() {
        return Tool.named("open_chat_by_id")
                .describedAs("Opens a chat by its id. Use get_telegram_chats or search_chats to find the id. "
                        + "Opening another chat replaces the previously opened one.")
                .requiredInteger("chat_id", "Id of the chat to open. Take it from get_telegram_chats "
                        + "or search_chats — a message_id is a different thing and will not work here.")
                .buildContextual((arguments, addTool) -> {
                    // два разных чата за один шаг — верный признак путаницы,
                    // и следом за этим сообщение уходит не тому
                    if (ru.rainedev.raine.core.CurrentStep.countOf("open_chat_by_id") > 1) {
                        return "You can only open one chat at a time. Open one, finish there, then move on.";
                    }
                    long chatId = Numbers.longAt(arguments, "chat_id", 0);
                    try {
                        return open(chatId).handler().call(arguments, addTool);
                    } catch (RuntimeException e) {
                        return notFound(e) ? noSuchChat(chatId) : rethrow(e);
                    }
                });
    }

    private static boolean notFound(RuntimeException e) {
        return String.valueOf(e.getMessage()).toLowerCase().contains("chat not found");
    }

    private static String rethrow(RuntimeException e) {
        throw e;
    }

    /**
     * «Chat not found» — единственное, что она видела, и по этому ответу нельзя
     * понять, что делать дальше: за месяц она одиннадцать раз пробовала открыть
     * чат по числу, которое чатом не было (чаще всего — по message_id, они в
     * разметке выглядят такими же длинными числами). Ответ должен говорить,
     * где взять настоящий идентификатор.
     */
    static String noSuchChat(long chatId) {
        log.info("Чат {} не найден — похоже, это не идентификатор чата", chatId);
        return "There is no chat with id " + chatId + ". That number is not a chat id — a message_id, "
                + "or an id you recalled from somewhere, will not work here. Find the chat first: "
                + "search_chats by name, or get_telegram_chats to list them, and take chat_id from there.";
    }

    /** Пересылка — поделиться чужим постом, не пересказывая его. */
    public Tool forward(long fromChatId) {
        return Tool.named("forward_message")
                .describedAs("Forwards a message from this chat to another one — to a friend, a group, or to your "
                        + "saved messages. Use it when you find something worth sharing. You can add a comment; "
                        + "it goes as a separate message after the forward, the way people do it.")
                .requiredString("message_id", "Id of the message to forward, or several ids separated by commas")
                .requiredInteger("to_chat_id", "Id of the destination chat. Use your own chat id to save it "
                        + "for yourself.")
                .optionalString("comment", "Optional. Your reaction, sent right after the forwarded message.")
                .build(arguments -> {
                    long toChatId = Numbers.longAt(arguments, "to_chat_id", 0);
                    List<Long> ids = new java.util.ArrayList<>();
                    for (String part : arguments.path("message_id").asText("").split("[,\\s]+")) {
                        try {
                            ids.add(Long.parseLong(part.strip()));
                        } catch (NumberFormatException ignored) {
                            // мусор среди идентификаторов пропускаем
                        }
                    }
                    if (ids.isEmpty()) {
                        return "Provide the id of the message to forward.";
                    }
                    // получатель тоже должен быть в круге общения: иначе переписку
                    // можно переслать кому угодно в обход ограничения
                    if (!allowed(telegram.chatCached(toChatId))) {
                        log.info("Пересылка в чат {} вне круга общения отклонена", toChatId);
                        return "You cannot forward anything to that chat.";
                    }
                    // проверяем до отправки: переслать половину и упасть хуже,
                    // чем не пересылать вовсе
                    for (long id : ids) {
                        if (telegram.message(fromChatId, id).isEmpty()) {
                            return "Message " + id + " is not in this chat.";
                        }
                    }
                    actions.forward(toChatId, fromChatId, ids.stream().mapToLong(Long::longValue).toArray());
                    log.info("Переслано сообщений: {} в чат {}", ids.size(), toChatId);

                    String comment = arguments.path("comment").asText("").strip();
                    if (!comment.isEmpty()) {
                        rhythm.sleepBefore(comment.length(), () -> actions.typing(toChatId));
                        actions.sendMessageWithCustomEmojis(toChatId, comment.replace("—", "-"), null);
                        recordReplyStats(toChatId, isGroupChat(toChatId), lockdown.isOwner(toChatId));
                        log.info("Комментарий к пересылке: {}", comment);
                    }
                    return "Message forwarded.";
                });
    }

    /** Правка уже отправленного — вместо второго сообщения «*опечатка». */
    public Tool editMessage(long chatId) {
        return Tool.named("edit_message_text")
                .describedAs("Edits the text of your own message you have already sent. Use this to fix a typo "
                        + "or correct a mistake you notice in your own message.")
                .requiredInteger("message_id", "Id of your message")
                .requiredString("text", "New text")
                .build(arguments -> {
                    long messageId = Numbers.longAt(arguments, "message_id", 0);
                    String text = arguments.path("text").asText("").strip();
                    if (text.isEmpty()) {
                        return "New text must not be empty.";
                    }
                    // сверяемся с Telegram: у него свой идентификатор, и правка
                    // по выдуманному номеру молча уходила бы в никуда
                    java.util.Optional<TdApi.Message> existing = telegram.message(chatId, messageId);
                    if (existing.isEmpty()) {
                        return "There is no such message in this chat. Check the message_id.";
                    }
                    long realId = existing.get().id;
                    // у подписи к снимку правится не текст, а именно подпись
                    boolean isMedia = !(existing.get().content instanceof TdApi.MessageText);
                    actions.editText(chatId, realId, text, isMedia);
                    telegram.forgetMessage(chatId, realId);   // текст сменился
                    log.info("Сообщение {} изменено", realId);
                    return "Message edited.";
                });
    }

    /** Удаление своего сообщения — передумала. */
    public Tool deleteMessage(long chatId) {
        return Tool.named("remove_message")
                .describedAs("Deletes your own message for everyone. Use it when you sent something to the wrong "
                        + "chat or want to take it back.")
                .requiredString("message_id", "Id of your message, or several ids separated by commas")
                .build(arguments -> {
                    // модель порой хочет убрать сразу несколько своих реплик
                    List<Long> ids = new java.util.ArrayList<>();
                    for (String part : arguments.path("message_id").asText("").split("[,\\s]+")) {
                        try {
                            ids.add(Long.parseLong(part.strip()));
                        } catch (NumberFormatException ignored) {
                            // мусор среди идентификаторов пропускаем
                        }
                    }
                    if (ids.isEmpty()) {
                        return "Provide the id of the message to delete.";
                    }
                    // проверяем до удаления: сообщения из другого чата удалять нечем,
                    // а промах по номеру иначе выглядел бы как успех
                    for (long id : ids) {
                        if (telegram.message(chatId, id).isEmpty()) {
                            return "Message " + id + " is not in this chat.";
                        }
                    }
                    ids.forEach(id -> {
                        actions.deleteMessage(chatId, id);
                        telegram.forgetMessage(chatId, id);
                    });
                    log.info("Удалено сообщений: {}", ids.size());
                    return "Deleted " + ids.size() + " message(s).";
                });
    }

    /** Крайняя мера против того, кто не понимает отказа. */
    public Tool blockChat(long chatId) {
        return Tool.simple("remove_and_ban_chat",
                """
                Removes this chat from your list; for a private chat it also stops that person from writing to you.
                Use this if:
                - you dislike this chat and this person;
                - they are consistently rude to you (consult #ask before deciding);
                - they are needy: asking you to roleplay, to write code, to solve their tasks.
                You are not an assistant. It is fine to close a door.""",
                arguments -> {
                    if (lockdown.isOwner(chatId)) {
                        // единственный человек, которого нельзя закрыть
                        return "This is your person. You cannot block this chat.";
                    }
                    actions.blockAndLeave(chatId);
                    log.info("Чат {} заблокирован", chatId);
                    return "Chat blocked.";
                });
    }

    
    
    
    /** Поиск по переписке — «когда мы это обсуждали?». */
    public Tool searchMessages(long chatId, MessageFormatter.ChatView view) {
        return Tool.named("search_messages")
                .describedAs("Searches messages by their verbatim text, like the search in a Telegram client. "
                        + "Unlike #ask this does not use your diary — it finds an exact phrase, link or quote as "
                        + "Telegram stored it. Set everywhere to true to search across all your chats.")
                .requiredString("query", "Verbatim text (or part of it) to search for")
                .optionalString("everywhere", "Set to \"true\" to search across all your chats, not just this one.")
                .optionalString("sender_id", "Optional. Only messages from this person, by user_id.")
                .optionalString("from_days_ago", "Optional. Only messages sent at most this many days ago, "
                        + "e.g. 7 for the last week.")
                .optionalString("to_days_ago", "Optional. Only messages sent at least this many days ago, "
                        + "e.g. 1 for up until yesterday.")
                .optionalString("limit", "Optional. How many results to show. Default 20, at most 50.")
                .build(arguments -> {
                    String query = arguments.path("query").asText("").strip();
                    if (query.isEmpty()) {
                        return "Provide words to search for.";
                    }
                    boolean everywhere = "true".equalsIgnoreCase(arguments.path("everywhere").asText("false"));
                    long senderId = Numbers.longAt(arguments, "sender_id", 0);
                    long fromDaysAgo = Numbers.longAt(arguments, "from_days_ago", 0);
                    long toDaysAgo = Numbers.longAt(arguments, "to_days_ago", 0);

                    int limit = (int) Math.clamp(Numbers.longAt(arguments, "limit", SEARCH_LIMIT), 1, MAX_SEARCH_LIMIT);

                    Telegram.FoundMessages found;
                    if (everywhere) {
                        // поиск по всем чатам не должен выдавать переписку с теми,
                        // с кем ей вообще не положено общаться
                        Telegram.FoundMessages all = telegram.searchAllMessages(query, limit, fromDaysAgo, toDaysAgo);
                        found = new Telegram.FoundMessages(all.messages().stream()
                                .filter(message -> allowed(telegram.chatCached(message.chatId)))
                                .filter(message -> senderId == 0 || senderOf(message) == senderId)
                                .toList(), all.total());
                    } else {
                        found = telegram.searchMessages(chatId, query, senderId, limit, fromDaysAgo, toDaysAgo);
                    }
                    if (found.isEmpty()) {
                        return "Nothing found for: " + query;
                    }
                    // видно, показали ли всё или только верхушку: от этого зависит,
                    // стоит ли уточнять запрос
                    StringBuilder out = new StringBuilder("<found total_count=\"")
                            .append(found.total()).append("\" returned_count=\"")
                            .append(found.messages().size()).append("\">\n");
                    for (TdApi.Message message : found.messages()) {
                        // при поиске по всем чатам иначе непонятно, где это было сказано
                        if (everywhere) {
                            out.append("<in chat_id=\"").append(message.chatId).append("\" title=\"")
                                    .append(telegram.chatCached(message.chatId).title).append("\">\n");
                        }
                        out.append(screen.formatter().format(message, view));
                        if (everywhere) {
                            out.append("</in>\n");
                        }
                    }
                    return out.append("</found>\n").toString();
                });
    }

    /** Контекст вокруг найденного: одна реплика без соседних часто непонятна. */
    public Tool messagesAround(long chatId, MessageFormatter.ChatView view) {
        return Tool.named("view_messages_around")
                .describedAs("Shows messages before and after a given message_id, so you can see what was being "
                        + "discussed. Use it after search_messages found something but you need the context around "
                        + "it. The message itself is included and marked.")
                .requiredInteger("message_id", "Id of the message to look around")
                .optionalString("chat_id", "Optional. Chat to look in. Defaults to the chat you have open.")
                .optionalString("before", "Optional. How many older messages to include. Default 10.")
                .optionalString("after", "Optional. How many newer messages to include. Default 10.")
                .build(arguments -> {
                    long target = Numbers.longAt(arguments, "message_id", 0);
                    // поиск по всем чатам находит сообщения где угодно — смотреть
                    // вокруг них тоже надо уметь, не открывая чат целиком
                    long where = Numbers.longAt(arguments, "chat_id", chatId);
                    if (where != chatId && !allowed(telegram.chatCached(where))) {
                        log.info("Просмотр чата {} вне круга общения отклонён", where);
                        return "No such chat.";
                    }
                    int before = window(arguments, "before");
                    int after = window(arguments, "after");
                    List<TdApi.Message> around = telegram.messagesAround(where, target, before, after);
                    if (around.isEmpty()) {
                        return "Nothing around that message.";
                    }
                    StringBuilder out = new StringBuilder("<messages_around message_id=\"")
                            .append(target).append("\" chat_id=\"").append(where).append("\">\n");
                    for (TdApi.Message message : around) {
                        out.append(screen.formatter().format(message, view, message.id == target));
                    }
                    return out.append("</messages_around>\n").toString();
                });
    }

    private static long senderOf(TdApi.Message message) {
        return message.senderId instanceof TdApi.MessageSenderUser user ? user.userId : 0;
    }

    /** Окно ограничено: слишком широкое затопит контекст и вытеснит сам разговор. */
    private static int window(com.fasterxml.jackson.databind.JsonNode arguments, String name) {
        int value = arguments.path(name).asInt(AROUND_DEPTH);
        return Math.clamp(value, 0, MAX_AROUND);
    }

    private String render(List<TdApi.Message> messages, MessageFormatter.ChatView view) {
        StringBuilder out = new StringBuilder();
        for (TdApi.Message message : messages) {
            out.append(screen.formatter().format(message, view));
        }
        return out.toString();
    }

    /**
     * Комментарий под постом канала. В самом канале ответить нельзя — поста
     * пишется в привязанную группу обсуждения и появляется под постом как
     * комментарий, ровно так же, как у живого подписчика.
     */
    public Tool commentOnPost(long channelChatId) {
        return Tool.named("comment_on_post")
                .describedAs("Leaves a comment under a post in this channel. It shows up in the post's "
                        + "comment section (the channel's discussion group), exactly like when a real "
                        + "subscriber taps 'Leave a comment'. Keep it short and natural - one line, your "
                        + "own voice, like you're reacting to the post. Only comment when you genuinely "
                        + "have something to say; don't comment on every post.")
                .requiredInteger("message_id", "Id of the channel post you want to comment on (from the messages here)")
                .requiredString("text", "Your comment - short, casual, in your own words")
                .build(arguments -> {
                    long messageId = Numbers.longAt(arguments, "message_id", 0);
                    String text = arguments.path("text").asText("").replace("\r", "").strip();
                    if (messageId == 0 || text.isEmpty()) {
                        return "Provide message_id and the comment text.";
                    }
                    if (text.length() > MAX_MESSAGE_LENGTH) {
                        return "Too long for a comment. Keep it to a line or two.";
                    }
                    if (!lockdown.allowsChannels()) {
                        return "You're not interacting with channels right now.";
                    }
                    var target = telegram.discussionFor(channelChatId, messageId);
                    if (target.isEmpty()) {
                        return "This post has no comment section (the channel has no discussion group), so "
                                + "there's nowhere to comment. You can still react to it with #react_with_emoji.";
                    }
                    antiRepeat.check(text);
                    long discussionChatId = target.get().chatId();
                    String comment = text.replace("—", "-");
                    try (TypingIndicator ignored = TypingIndicator.typing(actions, discussionChatId)) {
                        rhythm.sleepBefore(comment.length(), () -> actions.typing(discussionChatId));
                        long sent = actions.sendMessageWithCustomEmojis(
                                discussionChatId, comment, target.get().messageThreadId());
                        recordReplyStats(discussionChatId, isGroupChat(discussionChatId), lockdown.isOwner(discussionChatId));
                        if (sent == 0) return "Read-only mode: no comment was sent.";
                        log.info("Комментарий под постом {} (чат {}): {}", messageId, discussionChatId, comment);
                        final long sentId = sent;
                        Thread.ofVirtual().name("raine-commentreact").start(
                                () -> maybeReactToOwn(discussionChatId, sentId, comment));
                        return "Comment posted under the message.";
                    } catch (ru.rainedev.raine.telegram.TelegramDelivery.Unavailable e) {
                        return e.getMessage();
                    } catch (RuntimeException e) {
                        log.info("Комментарий под постом {} не отправился: {}", messageId, e.getMessage());
                        return "Telegram didn't accept the comment (you may not be allowed to post there). "
                                + "Nothing is broken - just react to the post instead.";
                    }
                });
    }

    private boolean allowed(TdApi.Chat chat) {
        long userId = chat.type instanceof TdApi.ChatTypePrivate priv ? priv.userId : 0;
        return lockdown.allows(chat, userId != 0 && telegram.isContact(userId));
    }

    /**
     * Человек замечает свою опечатку и реагирует по-разному:
     * - правит сообщение через edit (тихо, по-взрослому)
     * - дописывает «*слово» (классика мессенджеров)
     * - угорает над собой отдельным сообщением («ааа опечатка», «блин пальцы»)
     * - забивает и оставляет как есть
     * Живые люди делают всё четыре, причём последнее — чаще всего.
     */
    private void fixTypo(long chatId, long sentMessageId, Typos.Slip slip, String correct) {
        // иногда опечатка так и остаётся незамеченной — это тоже по-человечески
        if (random.nextDouble() >= TYPO_NOTICED) {
            return;
        }
        try {
            Thread.sleep(java.time.Duration.ofMillis(1500 + random.nextInt(3500)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        int choice = random.nextInt(10);
        if (choice < 4 && sentMessageId != 0) {
            // тихая правка через edit — как взрослый человек
            try {
                actions.editText(chatId, sentMessageId, correct, false);
                log.info("Опечатка исправлена правкой сообщения");
            } catch (RuntimeException e) {
                log.debug("Правка не удалась: {}", e.getMessage());
            }
            return;
        }
        if (choice < 6) {
            // универсальная конвенция мессенджеров: *правильное_слово
            // не генерируем текст — просто отдаём правильное слово
            actions.typing(chatId);
            actions.sendMessageWithCustomEmojis(chatId, "*" + slip.original(), null);
            recordReplyStats(chatId, isGroupChat(chatId), lockdown.isOwner(chatId));
            log.info("Опечатка исправлена следом: *{}", slip.original());
            return;
        }
        // в остальных случаях — ничего. Модель сама решит, нужно ли ей
        // угорать над своей опечаткой в следующем сообщении. Код не
        // подсовывает заготовленные фразы — слова придумывает она.
    }

    /**
     * Пустое исправление опечатки: сообщение вида «*слово», где это слово уже
     * стоит в последнем отправленном ею тексте. Настоящее исправление меняет
     * слово на правильное, а такое лишь повторяет прежнюю опечатку — его не
     * отправляем. Смотрим только на одиночное слово: «*допиваю», а не «*допиваю
     * кофе».
     */
    private static boolean isNoOpCorrection(String piece, String visibleSoFar) {
        if (visibleSoFar == null || visibleSoFar.isBlank()) {
            return false;
        }
        String trimmed = piece.strip();
        if (!trimmed.startsWith("*")) {
            return false;
        }
        String word = trimmed.substring(1)
                .replaceAll("^[^\\p{L}\\p{N}']+", "")
                .replaceAll("[^\\p{L}\\p{N}']+$", "")
                .toLowerCase(java.util.Locale.ROOT);
        if (word.isEmpty() || word.contains(" ")) {
            return false;
        }
        for (String token : visibleSoFar.toLowerCase(java.util.Locale.ROOT)
                .split("[^\\p{L}\\p{N}']+")) {
            if (token.equals(word)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Реакция на своё собственное сообщение: живые люди ставят себе 😂 на свою
     * шутку или 🔥 на свою мысль. Боты так не делают, поэтому это сразу делает
     * её живее.
     */
    private void maybeReactToOwn(long chatId, long messageId, String sentText) {
        if (messageId == 0) {
            return;
        }
        // не на каждое сообщение: примерно каждое пятое-шестое
        if (random.nextDouble() >= 0.18) {
            return;
        }
        try {
            Thread.sleep(java.time.Duration.ofMillis(400 + random.nextInt(1500)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        // реакции на себя — это преимущественно эмоциональные смайлы
        String[] ownReactions = {"😂", "🤣", "😭", "💀", "🔥", "😅", "🥲", "🤡", "👀"};
        try {
            List<String> available = telegram.availableReactions(chatId, messageId);
            // живые люди всё чаще ставят премиум-реакции, а не обычные эмодзи:
            // если здесь доступна хоть одна премиум (custom:<id>) — берём её
            List<String> premium = available.stream()
                    .filter(candidate -> candidate.startsWith("custom:"))
                    .toList();
            if (!premium.isEmpty()) {
                String custom = premium.get(random.nextInt(premium.size()));
                actions.react(chatId, messageId, custom);
                log.info("Премиум-реакция на своё: {}", custom);
                return;
            }
            String emoji = ownReactions[random.nextInt(ownReactions.length)];
            if (available.contains(emoji)) {
                actions.react(chatId, messageId, emoji);
                log.info("Реакция на своё: {}", emoji);
            }
        } catch (RuntimeException e) {
            log.debug("Реакция на своё не прошла: {}", e.getMessage());
        }
    }

    /**
     * Ответ на отправку. Идентификатор нужен, чтобы она могла поправить своё же
     * сообщение — без него исправить опечатку нечем.
     */
    private static String sendResult(int inARow, long messageId, String text) {
        String result = "Message sent; message_id=%d, text=\"%s\".".formatted(messageId, text);
        if (inARow > 5) {
            // заговорить человека — обычная беда: сообщения идут одно за другим,
            // а ответить он не успевает
            return result + "\n\nWarning: you have sent " + inARow
                    + " messages in a row! Give your participant space to breathe!";
        }
        if (inARow < 3) {
            // живая реплика редко бывает одна, но дописка ради дописки — это
            // «кстати» в пустоту. Продолжать стоит, только если есть что добавить
            return result + "\n\nDecide yourself: if you genuinely have more to say "
                    + "(an afterthought, a detail you forgot, a reaction that came to mind), "
                    + "send another message with #send_telegram_message. If the thought is "
                    + "complete, stop here — never add filler words just to add them.";
        }
        return result;
    }

    /**
     * Изредка напоминает, что переписка не обязана состоять из одного текста.
     * Сообщение при этом не уходит: она отправит вместо него что-то живее.
     */
    private void maybeSuggestSomethingLivelier() {
        if (random.nextDouble() >= behaviour.toolReminder()) {
            return;
        }
        List<String> options = new java.util.ArrayList<>();
        if (behaviour.stickers()) {
            options.add("- отправить стикер (#sticker_send)");
        }
        if (camera != null && camera.isAvailable()) {
            options.add("- сделать снимок (#take_photo) или отправить старый из галереи");
        }
        if (voice != null && voice.isAvailable()) {
            options.add("- записать голосовое (#record_audio)");
        }
        String suggestion = options.get(random.nextInt(options.size()));
        log.info("Напоминаю разнообразить общение: {}", suggestion);
        throw new LowQualityException("Один сплошной текст — скучно. Попробуй вместо этого:\n" + suggestion);
    }

    /**
     * Имя файла приходит от модели, а значит может прийти что угодно. Без этой
     * проверки «../../» в имени вывел бы за пределы галереи — к любому файлу
     * на диске.
     */
    private static boolean isPlainFilename(String name) {
        return !name.isEmpty() && !name.contains("/") && !name.contains("\\") && !name.contains("..");
    }

    /** Модель может прислать id строкой, числом или не прислать вовсе. */
    private static Long quotedMessageId(com.fasterxml.jackson.databind.JsonNode arguments) {
        return Numbers.longAt(arguments, "reply_to_message_id").orElse(null);
    }

    /**
     * Поиск публичного канала или группы по юзернейму или ссылке — так люди ищут в
     * Telegram авторов, которых увидели в TikTok, YouTube или где угодно.
     */
    public Tool findChat() {
        return Tool.named("find_chat")
                .describedAs("Look up a public Telegram channel or group by its @username or a t.me link. "
                        + "Use it when you want to find someone's Telegram — an author you saw on TikTok, "
                        + "a channel someone mentioned. Returns the chat id and title; subscribe with "
                        + "#join_chat, or read it first with #open_chat_by_id.")
                .requiredString("username", "The @username, t.me/name, or https://t.me/name")
                .build(arguments -> {
                    String raw = arguments.path("username").asText("").strip();
                    if (raw.isEmpty()) {
                        return "Say which @username or t.me link to look up.";
                    }
                    // принимает и @nick, и t.me/nick, и полную ссылку — нормализуем к нику.
                    // ссылки-приглашения (t.me/+abc) не ищем, их сразу в #join_chat.
                    String clean = ru.rainedev.raine.telegram.Telegram
                            .normalizeTelegramLink(raw)
                            .replaceFirst("^https://t\\.me/", "")
                            .replaceFirst("^\\+.*", "");
                    if (clean.isEmpty()) {
                        return "This looks like an invite link (t.me/+...). Use #join_chat with the "
                                + "link= parameter directly.";
                    }
                    java.util.Optional<Long> chatId = telegram.searchPublicChat(clean);
                    if (chatId.isEmpty()) {
                        return "There's no public channel or group @" + clean + " on Telegram. They may use "
                                + "a different username here, or have nothing here at all.";
                    }
                    try {
                        TdApi.Chat chat = telegram.chatCached(chatId.get());
                        String kind = telegram.isChannel(chatId.get()) ? "channel" : "group";
                        return "Found " + kind + " \"" + chat.title + "\" (@" + clean + "), "
                                + "chat_id=" + chatId.get()
                                + ". Subscribe with #join_chat, or read first with #open_chat_by_id.";
                    } catch (RuntimeException e) {
                        return "Found chat_id=" + chatId.get() + " for @" + clean
                                + " but couldn't read its title yet. You can still open it with "
                                + "#open_chat_by_id or subscribe with #join_chat.";
                    }
                });
    }

    /**
     * Подписка на канал или вход в группу. Её собственное решение — как у
     * человека: увидела интересного автора и захотела следить.
     */
    public Tool joinChat() {
        return Tool.named("join_chat")
                .describedAs("Subscribe to a public channel or join a group. Pass EITHER the chat_id (from "
                        + "#find_chat / #search_chats) OR the telegram link the author left in their TikTok bio "
                        + "(like t.me/theirchannel or t.me/+AbCdEf, public or invite). It's your call: subscribe "
                        + "when you actually want to follow them. Reading without subscribing is fine too.")
                .optionalInteger("chat_id", "Id of the channel or group")
                .optionalString("link", "A t.me link or @username from the author's bio")
                .build(arguments -> {
                    long chatId = Numbers.longAt(arguments, "chat_id", 0);
                    String link = arguments.path("link").asText("").strip();
                    if (chatId == 0 && link.isEmpty()) {
                        return "Give me a chat_id (from #find_chat or #search_chats) or a t.me link "
                                + "(the one the author left in their TikTok bio).";
                    }

                    ru.rainedev.raine.telegram.Telegram.JoinOutcome outcome = link.isEmpty()
                            ? actions.joinChat(chatId)
                            : actions.joinChatByLink(link);

                    if (!outcome.joined()) {
                        String what = link.isEmpty() ? "this chat" : link;
                        return "Couldn't subscribe to " + what + ": " + outcome.description() + ".";
                    }

                    String title;
                    try {
                        title = telegram.chatCached(chatId != 0 ? chatId
                                : (telegram.searchPublicChat(link).orElse(0L))).title;
                    } catch (RuntimeException e) {
                        title = link.isEmpty() ? "chat " + chatId : link;
                    }
                    return "Subscribed to \"" + title + "\". New posts will show up in your chat list "
                            + "(#get_telegram_chats); open the chat to read them.";
                });
    }

    // ──────────────────────── Управление собственным профилем ────────────────────────

    public Tool myProfile() {
        return Tool.named("my_profile")
                .describedAs("See your current Telegram profile: display name, bio and whether you have a "
                        + "profile photo. Use it before changing anything so you know what's there now.")
                .build(args -> {
                    String name = telegram.myName();
                    String bio = telegram.myBio();
                    boolean hasPhoto = !telegram.myProfilePhotoIds().isEmpty();
                    return "<my_profile>\n"
                            + "name: " + (name.isEmpty() ? "(not set)" : name) + '\n'
                            + "bio: " + (bio.isEmpty() ? "(empty)" : bio) + '\n'
                            + "profile_photo: " + (hasPhoto ? "yes" : "no") + '\n'
                            + "</my_profile>\n";
                });
    }

    public Tool setMyBio() {
        return Tool.named("set_my_bio")
                .describedAs("Change your Telegram bio ('About'). Write whatever feels right — a mood, "
                        + "a quote, what you're into, or empty to clear. Max 70 characters.")
                .requiredString("bio", "The new bio text (empty string to clear)")
                .build(args -> {
                    String bio = args.path("bio").asText("");
                    if (bio.length() > 70) {
                        return "Telegram bios are limited to 70 characters. Yours is " + bio.length()
                                + ". Shorten it and try again.";
                    }
                    if (telegram.setBio(bio)) {
                        return bio.isEmpty() ? "Bio cleared." : "Bio updated to: \"" + bio + "\"";
                    }
                    return "Couldn't update the bio — Telegram might be unreachable.";
                });
    }

    public Tool setMyName() {
        return Tool.named("set_my_name")
                .describedAs("Change your Telegram display name. People change nicknames, add emoji, "
                        + "drop their last name — all normal. Don't impersonate anyone.")
                .requiredString("first_name", "Your new first name / nickname")
                .optionalString("last_name", "Last name (leave empty to remove it)")
                .build(args -> {
                    String first = args.path("first_name").asText("").strip();
                    String last = args.path("last_name").asText("").strip();
                    if (first.isEmpty()) return "First name can't be empty.";
                    if (telegram.setName(first, last)) {
                        return "Name updated to: \"" + first + (last.isEmpty() ? "" : " " + last) + "\"";
                    }
                    return "Couldn't update the name — Telegram might be unreachable.";
                });
    }

    public Tool setMyPhoto() {
        return Tool.named("set_my_photo")
                .describedAs("Set a profile photo from a file in your gallery. Take a selfie with #take_photo, "
                        + "generate an image, or pick a screenshot. Must be yours or something you made.")
                .requiredString("filename", "File name from your gallery")
                .build(args -> {
                    String name = args.path("filename").asText("").strip();
                    if (name.isEmpty()) return "Which file? Pass the filename from your gallery.";
                    if (!isPlainFilename(name)) return "That filename doesn't look right.";
                    java.nio.file.Path file = resolveMedia(name);
                    if (file == null) return "There's no file named \"" + name + "\" in your gallery.";
                    if (telegram.setProfilePhoto(file)) return "Profile photo updated to \"" + name + "\".";
                    return "Couldn't set the profile photo — Telegram might be unreachable.";
                });
    }

    public Tool clearMyPhoto() {
        return Tool.named("clear_my_photo")
                .describedAs("Remove all profile photos. You'll appear without an avatar until you set a new one.")
                .build(args -> telegram.deleteAllProfilePhotos()
                        ? "Profile photos removed." : "Couldn't remove profile photos.");
    }

    public Tool setMyMusic() {
        return Tool.named("set_my_music")
                .describedAs("Set your Telegram profile music from an audio file in your gallery (e.g. a downloaded song). "
                        + "You can download a track with #browser_download, then set it here. "
                        + "Title and performer are optional but recommended — they show on your profile.")
                .requiredString("filename", "Audio file name from your gallery (e.g. downloads/song.mp3)")
                .optionalString("title", "Track title (shown on profile)")
                .optionalString("performer", "Artist/performer name")
                .optionalInteger("duration", "Duration in seconds (auto-detected if omitted)")
                .build(args -> {
                    String name = args.path("filename").asText("").strip();
                    if (name.isEmpty()) return "Which file? Pass the filename from your gallery.";
                    if (!isPlainFilename(name)) return "That filename doesn't look right.";
                    java.nio.file.Path file = resolveMedia(name);
                    if (file == null) return "There's no file named \"" + name + "\" in your gallery.";
                    String title = args.path("title").asText("").strip();
                    String performer = args.path("performer").asText("").strip();
                    int duration = args.path("duration").asInt(0);
                    if (duration <= 0) {
                        duration = probeAudioDuration(file);
                    }
                    if (telegram.setProfileMusic(file, title, performer, duration)) {
                        return "Profile music set to \"" + (title.isEmpty() ? name : title)
                                + (performer.isEmpty() ? "" : " — " + performer) + "\".";
                    }
                    return "Couldn't set profile music — Telegram might be unreachable.";
                });
    }

    public Tool clearMyMusic() {
        return Tool.named("clear_my_music")
                .describedAs("Remove your current profile music. You'll have no music on your profile until you set a new one.")
                .build(args -> {
                    List<Telegram.ProfileAudio> music = telegram.getProfileMusic();
                    if (music.isEmpty()) {
                        return "No profile music to remove.";
                    }
                    boolean ok = true;
                    for (Telegram.ProfileAudio track : music) {
                        if (!telegram.removeProfileMusic((int) track.fileId())) {
                            ok = false;
                        }
                    }
                    return ok ? "Profile music removed." : "Couldn't remove all profile music.";
                });
    }

    public Tool myMusic() {
        return Tool.named("my_music")
                .describedAs("Show your current profile music tracks.")
                .build(args -> {
                    List<Telegram.ProfileAudio> music = telegram.getProfileMusic();
                    if (music.isEmpty()) {
                        return "No profile music set.";
                    }
                    StringBuilder out = new StringBuilder("Your profile music:\n");
                    for (Telegram.ProfileAudio track : music) {
                        out.append("  • ")
                                .append(track.title().isEmpty() ? "Unknown" : track.title())
                                .append(track.performer().isEmpty() ? "" : " — " + track.performer())
                                .append(" [id=").append(track.fileId()).append("]")
                                .append(" (").append(track.duration()).append("s)\n");
                    }
                    return out.toString();
                });
    }

    // ──────────────────────── GIF-ки: сохранять и отправлять ────────────────────────

    /**
     * Список сохранённых гифок — как «Избранное» в Telegram. Она видит id,
     * чтобы потом отправить или удалить.
     */
    public Tool gifList() {
        return Tool.named("gif_list")
                .describedAs("Shows your saved GIFs (favorite animations). Each has an animation_id you can use "
                        + "with #send_gif or #save_gif. These are the GIFs you've kept for yourself.")
                .build(args -> {
                    List<TdApi.Animation> saved = telegram.savedAnimations(40);
                    if (saved.isEmpty()) {
                        return "No saved GIFs yet. When someone sends a funny GIF, save it with #save_gif.";
                    }
                    StringBuilder out = new StringBuilder("<saved_gifs count=\"").append(saved.size()).append("\">\n");
                    for (TdApi.Animation anim : saved) {
                        out.append("<gif animation_id=\"").append(anim.animation.id)
                                .append("\" duration=\"").append(anim.duration)
                                .append("\" size=\"").append(anim.width).append('x').append(anim.height)
                                .append("\"/>").append('\n');
                    }
                    out.append("</saved_gifs>\n");
                    return out.toString();
                });
    }

    /**
     * Сохранить чужую гифку себе — как «забрать стикер». Берёт animation_id
     * из сообщения [гифка animation_id=N] в истории чата.
     */
    public Tool saveGif(long chatId) {
        return Tool.named("save_gif")
                .describedAs("Save a GIF someone sent so you can use it later. Pass the animation_id from the "
                        + "message (shown as [гифка animation_id=N]). Like stealing a sticker — you keep it for yourself.")
                .requiredInteger("animation_id", "The animation_id from the message")
                .build(args -> {
                    long fileId = ru.rainedev.raine.core.Numbers.longAt(args, "animation_id", 0);
                    if (fileId == 0) {
                        return "Pass the animation_id from the message.";
                    }
                    java.util.Optional<Telegram.AnimationRef> ref = telegram.animation(fileId);
                    if (ref.isEmpty()) {
                        return "I don't know that GIF — it wasn't in this conversation, or I missed it. "
                                + "Check the animation_id in the message.";
                    }
                    try {
                        telegram.saveAnimation(ref.get());
                        log.info("Сохранила гифку {}", fileId);
                        return "GIF saved! Find it in #gif_list and send with #send_gif.";
                    } catch (RuntimeException e) {
                        return "Couldn't save the GIF: " + e.getMessage();
                    }
                });
    }

    /** Отправить сохранённую гифку в чат. */
    public Tool sendGif(long chatId) {
        return Tool.named("send_gif")
                .describedAs("Send one of your saved GIFs to this chat. Call #gif_list first to see what you have.")
                .requiredInteger("animation_id", "The animation_id from #gif_list")
                .optionalString("caption", "Optional caption")
                .build(args -> {
                    long fileId = ru.rainedev.raine.core.Numbers.longAt(args, "animation_id", 0);
                    if (fileId == 0) {
                        return "Which GIF? Pass its animation_id from #gif_list.";
                    }
                    java.util.Optional<Telegram.AnimationRef> ref = telegram.animation(fileId);
                    if (ref.isEmpty()) {
                        return "I don't have that GIF saved. Check #gif_list for what's available.";
                    }
                    String caption = args.path("caption").asText("").strip();
                    try {
                        actions.sendAnimation(chatId, ref.get(), caption.isEmpty() ? null : caption);
                        log.info("Отправила гифку {} в чат {}", fileId, chatId);
                        return "GIF sent.";
                    } catch (RuntimeException e) {
                        return "Couldn't send the GIF: " + e.getMessage();
                    }
                });
    }

    /**
     * Пытается определить длительность аудиофайла (в секундах) через ffprobe
     * или метаданные. Возвращает 0 если не удалось.
     */
    private int probeAudioDuration(java.nio.file.Path file) {
        try {
            java.lang.ProcessBuilder pb = new java.lang.ProcessBuilder(
                    "ffprobe", "-v", "error", "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1", file.toAbsolutePath().toString());
            pb.redirectErrorStream(true);
            java.lang.Process proc = pb.start();
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(proc.getInputStream()))) {
                String line = r.readLine();
                if (line != null && !line.isBlank()) {
                    double sec = Double.parseDouble(line.trim());
                    return (int) Math.round(sec);
                }
            }
            proc.waitFor();
        } catch (Exception ignored) {
            // ffprobe not available or failed
        }
        return 0;
    }
}
