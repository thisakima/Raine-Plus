package ru.rainedev.raine.telegram;

import it.tdlight.client.SimpleTelegramClient;
import it.tdlight.jni.TdApi;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Фасад над TDLib. Вызовы блокирующие: цикл живёт на виртуальном потоке,
 * поэтому ожидание ответа ничего не стоит и код читается сверху вниз.
 */
public final class Telegram {

    private static final Logger log = LoggerFactory.getLogger(Telegram.class);

    private final SimpleTelegramClient client;
    private final TelegramDelivery delivery;
    private final long myId;
    private final String characterName;
    private final Map<Long, String> nameCache = new ConcurrentHashMap<>();

    /**
     * Свойства чата спрашиваются по нескольку раз на каждое сообщение: заглушён ли,
     * закреплён ли, можно ли с ним говорить. Каждый такой вопрос — обращение
     * к Telegram, а их число ограничено.
     */
    private final Map<Long, Cached> chatCache = new ConcurrentHashMap<>();

    private record Cached(TdApi.Chat chat, long at) {
        boolean fresh() {
            return System.currentTimeMillis() - at < CHAT_CACHE_MILLIS;
        }
    }

    private static final long CHAT_CACHE_MILLIS = 15_000;

    /**
     * Одно и то же сообщение за ход спрашивается по нескольку раз: проверка
     * цитаты, пересылка, правка, удаление. Живёт столько же, сколько чат.
     */
    private final ShortLivedCache<String, TdApi.Message> messageCache = new ShortLivedCache<>(CHAT_CACHE_MILLIS);

    /** Ждём распознавание не дольше полуминуты: дальше разговор уже остыл. */
    private static final long RECOGNITION_TICK = 500;
    private static final int RECOGNITION_TIMEOUT_TICKS = 60;

    private Boolean premium;

    private final Throttle throttle = new Throttle();

    /** Каждое обращение к Telegram проходит через ограничитель. */
    private SimpleTelegramClient throttled() {
        throttle.allow();
        return client;
    }

    /**
     * Спросить Telegram и дождаться ответа — но не дольше разумного. Молчание
     * в ответ на один запрос иначе останавливает её целиком: она замирает
     * посреди хода и не отвечает никому.
     */
    private <T extends TdApi.Object> T ask(TdApi.Function<T> request) {
        return Await.reply(throttled().send(request), request.getClass().getSimpleName());
    }

    public Telegram(SimpleTelegramClient client, String characterName) {
        this(client, characterName, new TelegramDelivery(null));
    }

    public Telegram(SimpleTelegramClient client, String characterName, TelegramDelivery delivery) {
        this.client = client;
        this.delivery = delivery;
        this.characterName = characterName;
        this.myId = client.getMeAsync().join().id;
    }

    public long myId() {
        return myId;
    }

    public TelegramDelivery delivery() { return delivery; }

    /** All durable account mutations share confirmation and duplicate protection. */
    public <T extends TdApi.Object> T perform(TdApi.Function<T> request) {
        throttle.allow();
        return delivery.execute(request, () -> client.send(request));
    }

    /** Ephemeral UI state must not accumulate an offline backlog. */
    public <T extends TdApi.Object> void transientAction(TdApi.Function<T> request) {
        synchronized (delivery) {
            try { delivery.requireReady(); }
            catch (TelegramDelivery.Unavailable ignored) { return; }
            client.send(request).exceptionally(error -> {
                log.debug("Telegram transient action was not confirmed: {}", request.getClass().getSimpleName());
                return null;
            });
        }
    }

    public void reconcilePending() {
        delivery.reconcile(key -> client.send(new TdApi.GetMessage(key.chatId(), key.messageId())),
                key -> client.send(new TdApi.GetStory(key.chatId(), (int) key.messageId(), false)));
    }

    public void invalidateChatCache() { chatCache.clear(); }

    /** Свежие данные: непрочитанное и последнее сообщение меняются постоянно. */
    public TdApi.Chat chat(long chatId) {
        TdApi.Chat chat = ask(new TdApi.GetChat(chatId));
        chatCache.put(chatId, new Cached(chat, System.currentTimeMillis()));
        return chat;
    }

    /**
     * То же, но можно отдать недавний ответ. Для вопросов вроде «заглушён ли чат»
     * пятнадцати секунд давности достаточно, а обращение экономится.
     */
    public TdApi.Chat chatCached(long chatId) {
        Cached cached = chatCache.get(chatId);
        return cached != null && cached.fresh() ? cached.chat() : chat(chatId);
    }

    /**
     * Имя отправителя так, как его видит модель.
     * <p>
     * Свои сообщения помечаются особо — иначе она не отличает свои реплики
     * от чужих. К имени добавляется @username: именно по нему она потом
     * находит человека поиском, чтобы написать первой.
     */
    public String nameOf(long id) {
        if (id == 0) {
            return "";
        }
        if (id == myId) {
            return "You (" + characterName + ")";
        }
        return nameCache.computeIfAbsent(id, key -> {
            try {
                TdApi.User user = ask(new TdApi.GetUser(key));
                String name = (user.firstName + " " + user.lastName).strip();
                String username = usernameOf(user);
                if (!username.isEmpty()) {
                    name = name.isEmpty() ? "@" + username : name + " (@" + username + ")";
                }
                // имя человек задаёт себе сам, значит там может оказаться что угодно
                return ru.rainedev.raine.phone.ControlMarkers.sanitize(name);
            } catch (RuntimeException userLookupFailed) {
                try {
                    return ru.rainedev.raine.phone.ControlMarkers.sanitize(chat(key).title);
                } catch (RuntimeException e) {
                    log.debug("Не удалось определить имя для id {}", key);
                    return "";
                }
            }
        });
    }

    private static String usernameOf(TdApi.User user) {
        if (user.usernames == null || user.usernames.activeUsernames == null
                || user.usernames.activeUsernames.length == 0) {
            return "";
        }
        return user.usernames.activeUsernames[0];
    }

    /** История чата от старых к новым — в порядке чтения. */
    public List<TdApi.Message> history(long chatId, int limit) {
        List<TdApi.Message> collected = new ArrayList<>();
        long fromMessageId = 0;
        while (collected.size() < limit) {
            TdApi.Messages page =
                    ask(new TdApi.GetChatHistory(chatId, fromMessageId, 0, Math.min(50, limit), false));
            if (page.messages == null || page.messages.length == 0) {
                break;
            }
            collected.addAll(List.of(page.messages));
            fromMessageId = page.messages[page.messages.length - 1].id;
        }
        List<TdApi.Message> ordered = new ArrayList<>(collected.subList(0, Math.min(collected.size(), limit)));
        ordered.sort((a, b) -> Long.compare(a.id, b.id));
        return ordered;
    }

    /** Чаты из основного списка, самые свежие первыми. */
    public List<TdApi.Chat> chats(int limit) {
        TdApi.GetChats request = new TdApi.GetChats();
        request.chatList = new TdApi.ChatListMain();
        request.limit = limit;
        TdApi.Chats chats = ask(request);
        List<TdApi.Chat> result = new ArrayList<>();
        for (long id : chats.chatIds) {
            try {
                result.add(chat(id));
            } catch (RuntimeException e) {
                log.debug("Чат {} недоступен", id);
            }
        }
        return result;
    }

    /**
     * Подгружает список чатов с сервера. Пока он не загружен, поиск по своим
     * чатам возвращает пустоту — TDLib ищет только по тому, что уже известно.
     */
    public void loadChats(int limit) {
        try {
            TdApi.LoadChats request = new TdApi.LoadChats();
            request.chatList = new TdApi.ChatListMain();
            request.limit = limit;
            ask(request);
        } catch (RuntimeException e) {
            // «больше нечего загружать» — обычный ответ, а не ошибка
            log.debug("Список чатов загружен полностью: {}", e.getMessage());
        }
    }

    /**
     * Поиск человека или чата по всем доступным путям сразу: по своим чатам
     * находится только то, с кем уже есть переписка, публичный поиск отдаёт
     * в основном каналы, а человек из контактов, которому ещё не писали,
     * не находится ни там, ни там.
     * <p>
     * Найденное делится надвое, и разница существенная: со «своими» разговор
     * продолжают с того места, где он встал, а к «чужим» приходят с нуля,
     * и это совсем другой разговор.
     *
     * @param mine      те, с кем переписка уже есть, и люди из контактов
     * @param elsewhere публичные чаты и каналы, которые о ней ничего не знают
     */
    public record Found(List<TdApi.Chat> mine, List<TdApi.Chat> elsewhere) {
        public boolean isEmpty() {
            return mine.isEmpty() && elsewhere.isEmpty();
        }
    }

    public Found searchChats(String query, int limit) {
        String bare = query.startsWith("@") ? query.substring(1) : query;
        List<TdApi.Chat> mine = new ArrayList<>();
        List<TdApi.Chat> elsewhere = new ArrayList<>();

        // люди из контактов — даже те, с кем переписки ещё не было
        attempt("контакты", () -> {
            TdApi.SearchContacts request = new TdApi.SearchContacts();
            request.query = bare;
            request.limit = limit;
            for (long userId : ask(request).userIds) {
                add(mine, privateChat(userId));
            }
        });

        attempt("свои чаты", () -> {
            TdApi.SearchChatsOnServer request = new TdApi.SearchChatsOnServer();
            request.query = bare;
            request.limit = limit;
            for (long id : ask(request).chatIds) {
                add(mine, chat(id));
            }
        });

        if (query.startsWith("@")) {
            attempt("публичный чат", () -> {
                TdApi.SearchPublicChat request = new TdApi.SearchPublicChat();
                request.username = bare;
                add(elsewhere, ask(request));
            });
        }

        attempt("публичный поиск", () -> {
            TdApi.SearchPublicChats publicSearch = new TdApi.SearchPublicChats();
            publicSearch.query = bare;
            for (long id : ask(publicSearch).chatIds) {
                add(elsewhere, chat(id));
            }
        });

        // то, что уже нашлось среди своих, во второй список не попадает
        java.util.Set<Long> known = mine.stream().map(chat -> chat.id).collect(java.util.stream.Collectors.toSet());
        elsewhere.removeIf(chat -> known.contains(chat.id));

        return new Found(trim(mine, limit), trim(elsewhere, limit));
    }

    private static List<TdApi.Chat> trim(List<TdApi.Chat> chats, int limit) {
        return chats.size() > limit ? chats.subList(0, limit) : chats;
    }

    /**
     * Какие реакции Telegram примет для этого сообщения — его же словами.
     * <p>
     * Форму эмодзи угадывать нельзя: одни хранятся с невидимым вариационным
     * селектором, другие без, и набор отличается от чата к чату. Проще спросить.
     */
    public List<String> availableReactions(long chatId, long messageId) {
        List<String> emoji = new ArrayList<>();
        try {
            TdApi.GetMessageAvailableReactions request = new TdApi.GetMessageAvailableReactions();
            request.chatId = chatId;
            request.messageId = messageId;
            request.rowSize = 8;
            TdApi.AvailableReactions available = ask(request);
            collect(emoji, available.topReactions);
            collect(emoji, available.recentReactions);
            collect(emoji, available.popularReactions);
        } catch (RuntimeException e) {
            // не мелочь: без списка реакция не ставится вообще, а выглядит это
            // как «реакции иногда не работают» без единого следа в журнале
            log.info("Список доступных реакций для сообщения {} не получен: {}", messageId, e.getMessage());
        }
        return emoji;
    }

    private static void collect(List<String> into, TdApi.AvailableReaction[] reactions) {
        if (reactions == null) {
            return;
        }
        for (TdApi.AvailableReaction reaction : reactions) {
            if (reaction.type instanceof TdApi.ReactionTypeEmoji typed && !into.contains(typed.emoji)) {
                into.add(typed.emoji);
            }
            // премиум-реакции (custom emoji): добавляем как "custom:<id>",
            // чтобы модель могла их выбрать и поставить
            if (reaction.type instanceof TdApi.ReactionTypeCustomEmoji custom) {
                String tag = "custom:" + custom.customEmojiId;
                if (!into.contains(tag)) {
                    into.add(tag);
                }
            }
        }
    }

    /**
     * Ставит реакцию: обычную эмодзи или премиум (custom emoji).
     * Для custom emoji передаётся строка "custom:<id>".
     */
    public void reactWith(long chatId, long messageId, String reaction) {
        TdApi.ReactionType type;
        if (reaction.startsWith("custom:")) {
            try {
                long id = Long.parseLong(reaction.substring(7));
                type = new TdApi.ReactionTypeCustomEmoji(id);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid custom emoji id");
            }
        } else {
            type = new TdApi.ReactionTypeEmoji(reaction);
        }
        TdApi.AddMessageReaction request = new TdApi.AddMessageReaction();
        request.chatId = chatId;
        request.messageId = messageId;
        request.reactionType = type;
        request.updateRecentReactions = true;
        perform(request);
    }

    /**
     * Скачивает файл и отдаёт путь к нему. TDLib кладёт файлы к себе в каталог
     * сессии и переиспользует уже скачанное.
     */
    public Optional<java.nio.file.Path> download(int fileId) {
        try {
            TdApi.DownloadFile request = new TdApi.DownloadFile();
            request.fileId = fileId;
            request.priority = 16;
            request.synchronous = true;   // ждём готовности, а не подписываемся на обновления
            TdApi.File file = ask(request);
            if (file.local == null || file.local.path == null || file.local.path.isEmpty()) {
                return Optional.empty();
            }
            java.nio.file.Path path = java.nio.file.Path.of(file.local.path);
            return java.nio.file.Files.exists(path) ? Optional.of(path) : Optional.empty();
        } catch (RuntimeException e) {
            log.debug("Не удалось скачать файл {}: {}", fileId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Распознаёт речь силами Telegram Premium — без внешних сервисов и оплаты
     * за минуты. Результат приходит не сразу, поэтому ждём его опросом.
     */
    public Optional<String> recognizeSpeech(long chatId, long messageId) {
        Optional<String> cached = message(chatId, messageId).flatMap(Telegram::transcriptOf);
        if (cached.isPresent()) {
            return cached;
        }
        if (!isPremium()) {
            return Optional.empty();
        }
        try {
            TdApi.RecognizeSpeech request = new TdApi.RecognizeSpeech();
            request.chatId = chatId;
            request.messageId = messageId;
            ask(request);
        } catch (RuntimeException e) {
            log.debug("Распознавание не запустилось: {}", e.getMessage());
            return Optional.empty();
        }

        for (int waited = 0; waited < RECOGNITION_TIMEOUT_TICKS; waited++) {
            try {
                Thread.sleep(RECOGNITION_TICK);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
            Optional<String> ready = message(chatId, messageId).flatMap(Telegram::transcriptOf);
            if (ready.isPresent()) {
                return ready;
            }
        }
        log.debug("Распознавание речи не успело за отведённое время");
        return Optional.empty();
    }

    /** Есть ли премиум — без него распознавания не будет. */
    public boolean isPremium() {
        if (premium == null) {
            try {
                premium = ask(new TdApi.GetUser(myId)).isPremium;
                if (!premium) {
                    log.info("Премиума нет: голосовые останутся нераспознанными");
                }
            } catch (RuntimeException e) {
                premium = false;
            }
        }
        return premium;
    }

    private static Optional<String> transcriptOf(TdApi.Message message) {
        TdApi.SpeechRecognitionResult result = switch (message.content) {
            case TdApi.MessageVoiceNote voice -> voice.voiceNote.speechRecognitionResult;
            case TdApi.MessageVideoNote video -> video.videoNote.speechRecognitionResult;
            case null, default -> null;
        };
        if (result instanceof TdApi.SpeechRecognitionResultText text && !text.text.isBlank()) {
            return Optional.of(text.text);
        }
        return Optional.empty();
    }

    /** Полная аватарка чата — та, что открывается по нажатию, а не миниатюра. */
    public Optional<TdApi.ChatPhoto> chatPhoto(long chatId) {
        try {
            TdApi.Chat chat = chat(chatId);
            if (chat.type instanceof TdApi.ChatTypePrivate priv) {
                TdApi.UserFullInfo info = ask(new TdApi.GetUserFullInfo(priv.userId));
                return Optional.ofNullable(info.photo);
            }
            // у групп и каналов полной истории аватарок нет — берём текущую
            if (chat.photo == null) {
                return Optional.empty();
            }
            TdApi.ChatPhoto current = new TdApi.ChatPhoto();
            TdApi.PhotoSize size = new TdApi.PhotoSize();
            size.photo = chat.photo.big;
            current.sizes = new TdApi.PhotoSize[] {size};
            return Optional.of(current);
        } catch (RuntimeException e) {
            log.debug("Аватарка чата {} недоступна: {}", chatId, e.getMessage());
            return Optional.empty();
        }
    }

    /** Аватарка пользователя по userId (для профилей в отношениях). */
    public Optional<TdApi.ChatPhoto> userPhoto(long userId) {
        try {
            TdApi.UserFullInfo info = ask(new TdApi.GetUserFullInfo(userId));
            return Optional.ofNullable(info.photo);
        } catch (RuntimeException e) {
            log.debug("Аватарка пользователя {} недоступна: {}", userId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Активные истории в её чатах. Отдельного запроса «все чаты со сторис»
     * в этой сборке нет, поэтому перебираем её чаты и спрашиваем по каждому —
     * вызов дорогой, зовут его только когда она реально хочет посмотреть.
     */
    public record StoryRef(long chatId, String title, int storyId) {}

    public List<StoryRef> activeStories(int chatLimit, int storiesPerChat) {
        List<StoryRef> result = new ArrayList<>();
        for (TdApi.Chat chat : chats(chatLimit)) {
            try {
                TdApi.ChatActiveStories active = ask(new TdApi.GetChatActiveStories(chat.id));
                if (active == null || active.stories == null) {
                    continue;
                }
                int taken = 0;
                for (TdApi.StoryInfo info : active.stories) {
                    if (taken >= storiesPerChat) {
                        break;
                    }
                    result.add(new StoryRef(chat.id, chat.title, info.storyId));
                    taken++;
                }
            } catch (RuntimeException e) {
                // у большинства чатов историй нет — это норма, а не ошибка
                log.debug("Истории чата {} недоступны", chat.title);
            }
        }
        return result;
    }

    /** Личный чат с пользователем. Объект создаётся локально, человек ничего не узнает. */
    public TdApi.Chat privateChat(long userId) {
        TdApi.CreatePrivateChat request = new TdApi.CreatePrivateChat();
        request.userId = userId;
        return ask(request);
    }

    /**
     * Может ли она публиковать посты в канале. Нужно, чтобы в собственном
     * канале был доступен инструмент отправки, а в чужих — не было: попытка
     * отправить в чужой канал без прав всё равно упадёт, но позже и менее
     * понятно.
     */
    public boolean canPostMessages(long chatId) {
        try {
            TdApi.GetChatMember request = new TdApi.GetChatMember();
            request.chatId = chatId;
            request.memberId = new TdApi.MessageSenderUser(myId);
            TdApi.ChatMember member = ask(request);
            if (member.status instanceof TdApi.ChatMemberStatusCreator) {
                return true;
            }
            if (member.status instanceof TdApi.ChatMemberStatusAdministrator admin) {
                return admin.rights != null && admin.rights.canPostMessages;
            }
            return false;
        } catch (RuntimeException e) {
            log.debug("Не удалось проверить права постинга в чате {}: {}", chatId, e.getMessage());
            return false;
        }
    }

    /**
     * Куда писать комментарий под постом канала и к какому сообщению отвечать.
     * <p>
     * Комментарии к постам живут в привязанной к каналу группе обсуждения: сам
     * пост туда автоматически пересылается, и реплика в ответ на эту пересылку
     * появляется под постом как комментарий. {@link TdApi.GetMessageThread}
     * возвращает и чат обсуждения, и сообщение-ветку, к которому надо отвечать.
     *
     * @return чат группы обсуждения и id сообщения-ветки; пусто, если у поста
     *         нет группы обсуждения (комментарии в канале выключены)
     */
    public Optional<DiscussionTarget> discussionFor(long channelChatId, long messageId) {
        try {
            TdApi.MessageThreadInfo info = ask(new TdApi.GetMessageThread(channelChatId, messageId));
            if (info == null || info.chatId == 0 || info.messageThreadId == 0) {
                return Optional.empty();
            }
            return Optional.of(new DiscussionTarget(info.chatId, info.messageThreadId));
        } catch (RuntimeException e) {
            // у поста без группы обсуждения ветки нет — это норма, а не ошибка
            log.debug("Обсуждение поста {} в канале {} не найдено: {}",
                    messageId, channelChatId, e.getMessage());
            return Optional.empty();
        }
    }

    /** Привязанная к каналу группа обсуждения: куда и к чему писать комментарий. */
    public record DiscussionTarget(long chatId, long messageThreadId) {}

    /** Может ли публиковать истории — это право Telegram Premium. */
    public boolean canPostStories(long chatId) {
        if (!isPremium()) {
            return false;
        }
        try {
            TdApi.GetChatMember request = new TdApi.GetChatMember();
            request.chatId = chatId;
            request.memberId = new TdApi.MessageSenderUser(myId);
            TdApi.ChatMember member = ask(request);
            if (member.status instanceof TdApi.ChatMemberStatusCreator) {
                return true;
            }
            if (member.status instanceof TdApi.ChatMemberStatusAdministrator admin) {
                return admin.rights != null && admin.rights.canPostStories;
            }
            return false;
        } catch (RuntimeException e) {
            log.debug("Не удалось проверить права на истории в чате {}: {}", chatId, e.getMessage());
            return false;
        }
    }

    /**
     * Публикует историю от её имени. Для своих историй Telegram требует
     * идентификатор чата «Избранное», то есть личный чат с собой.
     * <p>
     * Период жизни истории — сутки: меньше ставить не хочется, больше
     * без Telegram Premium нельзя.
     */
    public long postStory(java.nio.file.Path photoPath, String caption) {
        TdApi.Chat saved = privateChat(myId);
        TdApi.PostStory request = new TdApi.PostStory();
        request.chatId = saved.id;
        TdApi.InputStoryContentPhoto content = new TdApi.InputStoryContentPhoto();
        content.photo = new TdApi.InputFileLocal(photoPath.toAbsolutePath().toString());
        content.addedStickerFileIds = new int[0];
        request.content = content;
        request.areas = new TdApi.InputStoryAreas(new TdApi.InputStoryArea[0]);
        TdApi.FormattedText text = new TdApi.FormattedText();
        text.text = caption == null ? "" : caption;
        text.entities = new TdApi.TextEntity[0];
        request.caption = text;
        TdApi.StoryPrivacySettingsEveryone privacy = new TdApi.StoryPrivacySettingsEveryone();
        privacy.exceptUserIds = new long[0];
        request.privacySettings = privacy;
        request.albumIds = new int[0];
        request.activePeriod = 86400;
        request.fromStoryFullId = null;
        request.isPostedToChatPage = true;
        request.protectContent = false;
        return perform(request).id;
    }

    /**
     * Публикует видеоисторию — например, красивый эдит из TikTok. Отличается
     * от фотоистории только содержимым: {@link TdApi.InputStoryContentVideo}
     * вместо фото. Обложку берёт с первого кадра, длительность Telegram
     * определит сам по файлу.
     */
    public long postVideoStory(java.nio.file.Path videoPath, String caption) {
        TdApi.Chat saved = privateChat(myId);
        TdApi.PostStory request = new TdApi.PostStory();
        request.chatId = saved.id;
        TdApi.InputStoryContentVideo content = new TdApi.InputStoryContentVideo();
        content.video = new TdApi.InputFileLocal(videoPath.toAbsolutePath().toString());
        content.addedStickerFileIds = new int[0];
        content.coverFrameTimestamp = 0.0;
        content.isAnimation = false;
        request.content = content;
        request.areas = new TdApi.InputStoryAreas(new TdApi.InputStoryArea[0]);
        TdApi.FormattedText text = new TdApi.FormattedText();
        text.text = caption == null ? "" : caption;
        text.entities = new TdApi.TextEntity[0];
        request.caption = text;
        TdApi.StoryPrivacySettingsEveryone privacy = new TdApi.StoryPrivacySettingsEveryone();
        privacy.exceptUserIds = new long[0];
        request.privacySettings = privacy;
        request.albumIds = new int[0];
        request.activePeriod = 86400;
        request.fromStoryFullId = null;
        request.isPostedToChatPage = true;
        request.protectContent = false;
        return perform(request).id;
    }

    /** Закреплён ли чат — закрепляют то, что важно, и это стоит замечать. */
    public boolean isPinned(long chatId) {
        try {
            TdApi.Chat chat = chatCached(chatId);
            if (chat.positions == null) {
                return false;
            }
            for (TdApi.ChatPosition position : chat.positions) {
                if (position.isPinned) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Заглушён ли чат — по этому видно, насколько он важен владельцу. */
    public boolean isMuted(long chatId) {
        try {
            TdApi.Chat chat = chatCached(chatId);
            return chat.notificationSettings != null && chat.notificationSettings.muteFor > 0;
        } catch (RuntimeException e) {
            return false;
        }
    }



    /** Записан ли человек в контакты — от этого зависит, позволено ли с ним говорить. */
    public boolean isContact(long userId) {
        try {
            return ask(new TdApi.GetUser(userId)).isContact;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Ищет публичный чат или канал по юзернейму (@name). Возвращает chatId
     * или пусто, если не нашлось.
     */
    public java.util.Optional<Long> searchPublicChat(String username) {
        if (username == null || username.isBlank()) {
            return java.util.Optional.empty();
        }
        String clean = username.strip().replaceFirst("^@", "");
        try {
            TdApi.Chat chat = ask(new TdApi.SearchPublicChat(clean));
            return chat == null ? java.util.Optional.empty() : java.util.Optional.of(chat.id);
        } catch (RuntimeException e) {
            log.debug("Публичный чат @{} не найден: {}", clean, e.getMessage());
            return java.util.Optional.empty();
        }
    }

    /**
     * Вступает в чат/канал по chatId. Работает для публичных каналов и групп
     * с открытым доступом. Для приватных нужна ссылка-приглашение.
     */
    /**
     * Чем закончилась попытка вступить в чат. Telegram отвечает по-разному:
     * «зашла», «заявка отправлена и ждёт одобрения», «отказали». Говорить
     * «подписалась», когда на самом деле заявка висит, — врать ей.
     */
    public record JoinOutcome(boolean joined, String description) {
        static JoinOutcome of(TdApi.ChatJoinResult result) {
            if (result instanceof TdApi.ChatJoinResultSuccess) {
                return new JoinOutcome(true, "joined");
            }
            if (result instanceof TdApi.ChatJoinResultRequestSent) {
                return new JoinOutcome(false, "the request was sent and is waiting for approval");
            }
            if (result instanceof TdApi.ChatJoinResultDeclined) {
                return new JoinOutcome(false, "the admins declined the request");
            }
            if (result instanceof TdApi.ChatJoinResultGuardBotApprovalRequired) {
                return new JoinOutcome(false, "this chat needs approval from a guard bot");
            }
            return new JoinOutcome(false, "Telegram didn't confirm the join");
        }
    }

    /** Вступление в чат по id с подробным результатом. */
    public JoinOutcome joinChatDetailed(long chatId) {
        try {
            JoinOutcome outcome = JoinOutcome.of(perform(new TdApi.JoinChat(chatId)));
            log.info("Вступление в чат {}: {}", chatId, outcome.description());
            return outcome;
        } catch (TelegramDelivery.Unavailable offline) {
            return new JoinOutcome(false, "Telegram is unreachable right now");
        } catch (RuntimeException e) {
            log.debug("Не удалось вступить в чат {}: {}", chatId, e.getMessage());
            return new JoinOutcome(false, "Telegram refused: " + (e.getMessage() == null
                    ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    /**
     * Вступление по ссылке {@code t.me/...} — публичный ник или приглашение
     * {@code t.me/+abc}. Именно такие ссылки авторы оставляют в bio.
     */
    public JoinOutcome joinByLink(String link) {
        String url = normalizeTelegramLink(link);
        if (url.isEmpty()) {
            return new JoinOutcome(false, "that doesn't look like a Telegram link");
        }
        boolean invite = url.contains("/+") || url.contains("/joinchat/");
        if (invite) {
            try {
                JoinOutcome outcome = JoinOutcome.of(perform(new TdApi.JoinChatByInviteLink(url)));
                log.info("Вступление по приглашению {}: {}", url, outcome.description());
                return outcome;
            } catch (TelegramDelivery.Unavailable offline) {
                return new JoinOutcome(false, "Telegram is unreachable right now");
            } catch (RuntimeException e) {
                log.debug("Приглашение {} не сработало: {}", url, e.getMessage());
                return new JoinOutcome(false, "the invite link didn't work (it may have expired)");
            }
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("t\\.me/([A-Za-z][A-Za-z0-9_]{3,})").matcher(url);
        if (!m.find()) {
            return new JoinOutcome(false, "that doesn't look like a Telegram link");
        }
        java.util.Optional<Long> chatId = searchPublicChat(m.group(1));
        if (chatId.isEmpty()) {
            return new JoinOutcome(false, "there's no public chat @" + m.group(1) + " on Telegram");
        }
        return joinChatDetailed(chatId.get());
    }

    /** Приводит «@name», «t.me/name», «https://t.me/+abc» к виду {@code https://t.me/...}. */
    public static String normalizeTelegramLink(String link) {
        if (link == null || link.isBlank()) {
            return "";
        }
        String value = link.strip();
        value = value.replaceFirst("(?i)^https?://", "");
        value = value.replaceFirst("(?i)^(?:www\\.)?t(?:elegram)?\\.me/", "");
        value = value.replaceFirst("^@", "");
        value = value.replaceFirst("^/", "");
        if (value.isBlank()) {
            return "";
        }
        return "https://t.me/" + value;
    }

    /** Проверяет, является ли чат каналом. */
    public boolean isChannel(long chatId) {
        try {
            TdApi.Chat chat = chatCached(chatId);
            return chat.type instanceof TdApi.ChatTypeSupergroup sg && sg.isChannel;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Сохраняет человека в контакты — так его потом можно найти по имени. */
    public void addContact(long userId, String firstName, String lastName) {
        TdApi.ImportedContact contact = new TdApi.ImportedContact();
        contact.firstName = firstName;
        contact.lastName = lastName == null ? "" : lastName;

        TdApi.AddContact request = new TdApi.AddContact();
        request.userId = userId;
        request.contact = contact;
        request.sharePhoneNumber = false;   // свой номер не раздаём
        perform(request);

        nameCache.remove(userId);
    }

    public List<TdApi.User> contacts(int limit) {
        List<TdApi.User> result = new ArrayList<>();
        try {
            long[] ids = ask(new TdApi.GetContacts()).userIds;
            for (int i = 0; i < Math.min(ids.length, limit); i++) {
                result.add(ask(new TdApi.GetUser(ids[i])));
            }
        } catch (RuntimeException e) {
            log.debug("Контакты недоступны: {}", e.getMessage());
        }
        return result;
    }

    private static void add(List<TdApi.Chat> found, TdApi.Chat chat) {
        if (chat != null && found.stream().noneMatch(known -> known.id == chat.id)) {
            found.add(chat);
        }
    }

    private static void attempt(String what, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.debug("Поиск «{}» не дал результата: {}", what, e.getMessage());
        }
    }

    /**
     * Поиск по тексту внутри чата, при желании только по одному отправителю.
     */
    /**
     * Найденное вместе с тем, сколько его всего. Без общего числа не понять,
     * показали ли всё или верхушку, — а от этого зависит, уточнять ли запрос.
     */
    public record FoundMessages(List<TdApi.Message> messages, int total) {
        public boolean isEmpty() {
            return messages.isEmpty();
        }
    }

    public FoundMessages searchMessages(long chatId, String query, long senderId, int limit,
                                        long fromDaysAgo, long toDaysAgo) {
        TdApi.SearchChatMessages request = new TdApi.SearchChatMessages();
        request.chatId = chatId;
        request.query = query;
        request.limit = limit;
        if (senderId != 0) {
            request.senderId = new TdApi.MessageSenderUser(senderId);
        }
        TdApi.FoundChatMessages found = ask(request);
        if (found.messages == null) {
            return new FoundMessages(List.of(), 0);
        }
        // у поиска внутри чата нет диапазона дат в запросе — отсеиваем после
        List<TdApi.Message> messages = java.util.Arrays.stream(found.messages)
                .filter(message -> withinDays(message.date, fromDaysAgo, toDaysAgo))
                .toList();
        return new FoundMessages(messages, found.totalCount);
    }

    private static boolean withinDays(int date, long fromDaysAgo, long toDaysAgo) {
        long now = System.currentTimeMillis() / 1000;
        if (fromDaysAgo > 0 && date < now - fromDaysAgo * 86400) {
            return false;
        }
        return !(toDaysAgo > 0 && date > now - toDaysAgo * 86400);
    }

    /**
     * Поиск по всем чатам сразу — когда неизвестно, где это было сказано.
     *
     * @param fromDaysAgo не старше стольких дней назад, 0 — без ограничения
     * @param toDaysAgo   не позже стольких дней назад, 0 — до сих пор
     */
    public FoundMessages searchAllMessages(String query, int limit, long fromDaysAgo, long toDaysAgo) {
        try {
            TdApi.SearchMessages request = new TdApi.SearchMessages();
            request.query = query;
            request.limit = limit;
            // «между неделей назад и вчера» — обычный человеческий способ вспоминать
            long now = System.currentTimeMillis() / 1000;
            request.minDate = fromDaysAgo > 0 ? (int) (now - fromDaysAgo * 86400) : 0;
            request.maxDate = toDaysAgo > 0 ? (int) (now - toDaysAgo * 86400) : 0;
            TdApi.FoundMessages found = ask(request);
            return found.messages == null
                    ? new FoundMessages(List.of(), 0)
                    : new FoundMessages(List.of(found.messages), found.totalCount);
        } catch (RuntimeException e) {
            log.debug("Поиск по всем чатам не удался: {}", e.getMessage());
            return new FoundMessages(List.of(), 0);
        }
    }

    /** Соседние сообщения — чтобы понять, о чём вообще шла речь. */
    public List<TdApi.Message> messagesAround(long chatId, long messageId, int before, int after) {
        TdApi.GetChatHistory request = new TdApi.GetChatHistory();
        request.chatId = chatId;
        request.fromMessageId = messageId;
        // отрицательное смещение захватывает и то, что было после искомого
        request.offset = -after;
        request.limit = after + 1 + before;
        TdApi.Messages messages = ask(request);
        List<TdApi.Message> result = messages.messages == null
                ? new ArrayList<>() : new ArrayList<>(List.of(messages.messages));
        result.sort((a, b) -> Long.compare(a.id, b.id));
        return result;
    }

    /**
     * Стикеры, попадавшиеся на глаза: свои и присланные другими.
     * <p>
     * У стикера два разных идентификатора — собственный и файловый. Модель
     * оперирует первым, а Telegram при отправке ждёт второй, поэтому связь
     * между ними приходится помнить.
     */
    /**
     * Стикер так, как его понимает Telegram при отправке.
     * <p>
     * Здесь два разных идентификатора: локальный живёт только внутри текущей
     * сессии, а удалённый — постоянный, и именно им стикер отправляют. После
     * перезапуска локальный номер уже ничего не значит.
     *
     * @param remoteId постоянный идентификатор файла
     */
    public record StickerRef(int fileId, String remoteId, String emoji, int width, int height) {}

    private final Map<Long, StickerRef> stickerFiles = new ConcurrentHashMap<>();

    public void rememberSticker(TdApi.Sticker sticker) {
        if (sticker == null || sticker.sticker == null) {
            return;
        }
        String remote = sticker.sticker.remote == null ? "" : sticker.sticker.remote.id;
        stickerFiles.put(sticker.id, new StickerRef(sticker.sticker.id, remote,
                sticker.emoji == null ? "" : sticker.emoji, sticker.width, sticker.height));
    }

    /**
     * Гифка так, как её понимает Telegram при отправке: локальный номер живёт
     * только внутри сессии, а удалённый — постоянный, им и отправляют.
     */
    public record AnimationRef(int fileId, String remoteId, int duration, int width, int height) {}

    private final Map<Long, AnimationRef> animations = new ConcurrentHashMap<>();

    /**
     * Запоминает гифку по локальному id файла. Как и со стикерами: без этого
     * её нельзя ни переслать, ни отправить потом.
     */
    public void rememberAnimation(TdApi.Animation animation) {
        if (animation == null || animation.animation == null) {
            return;
        }
        String remote = animation.animation.remote == null ? "" : animation.animation.remote.id;
        animations.put((long) animation.animation.id, new AnimationRef(
                animation.animation.id, remote,
                animation.duration, animation.width, animation.height));
    }

    /** Что известно про гифку по локальному id файла. */
    public Optional<AnimationRef> animation(long fileId) {
        return Optional.ofNullable(animations.get(fileId));
    }

    /** Сохранённые в Telegram гифки (избранные анимации). */
    public List<TdApi.Animation> savedAnimations(int limit) {
        List<TdApi.Animation> result = new ArrayList<>();
        try {
            TdApi.Animations saved = ask(new TdApi.GetSavedAnimations());
            for (TdApi.Animation animation : saved.animations) {
                rememberAnimation(animation);
                result.add(animation);
                if (result.size() >= limit) {
                    break;
                }
            }
        } catch (RuntimeException e) {
            log.debug("Сохранённые гифки недоступны: {}", e.getMessage());
        }
        return result;
    }

    /** Добавляет гифку в избранное — «забрать себе», как стикер. */
    public void saveAnimation(AnimationRef animation) {
        TdApi.AddSavedAnimation request = new TdApi.AddSavedAnimation(
                animation.remoteId().isEmpty()
                        ? new TdApi.InputFileId(animation.fileId())
                        : new TdApi.InputFileRemote(animation.remoteId()));
        perform(request);
    }

    /** @return что известно про стикер или пусто, если такой не попадался */
    public Optional<StickerRef> sticker(long stickerId) {
        return Optional.ofNullable(stickerFiles.get(stickerId));
    }

    /**
     * Пак целиком по короткому имени — тому, что стоит в ссылке
     * {@code t.me/addstickers/<имя>}. Стикеры пака запоминаются: без этого
     * их нельзя ни отправить, ни забрать себе поштучно.
     */
    public Optional<TdApi.StickerSet> stickerSet(String nameOrLink) {
        String name = stickerSetName(nameOrLink);
        if (name.isEmpty()) {
            return Optional.empty();
        }
        try {
            TdApi.SearchStickerSet request = new TdApi.SearchStickerSet();
            request.name = name;
            TdApi.StickerSet set = ask(request);
            for (TdApi.Sticker sticker : set.stickers) {
                rememberSticker(sticker);
            }
            return Optional.of(set);
        } catch (RuntimeException e) {
            log.debug("Пак {} не нашёлся: {}", name, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Поставить пак себе. Без этого он живёт только до перезапуска и не виден
     * в самом Telegram — а стикеры из неустановленного пака человек не пришлёт.
     */
    public void installStickerSet(long setId) {
        TdApi.ChangeStickerSet request = new TdApi.ChangeStickerSet();
        request.setId = setId;
        request.isInstalled = true;
        perform(request);
    }

    /** Свои паки: по ним видно, из чего вообще есть что выбрать. */
    public List<TdApi.StickerSetInfo> stickerSets() {
        try {
            TdApi.GetInstalledStickerSets request = new TdApi.GetInstalledStickerSets();
            request.stickerType = new TdApi.StickerTypeRegular();
            return List.of(ask(request).sets);
        } catch (RuntimeException e) {
            log.debug("Установленные паки недоступны: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Имя пака модель приносит как придётся: ссылкой, ссылкой без схемы,
     * с собачкой или просто именем. Telegram же понимает только короткое имя.
     */
    static String stickerSetName(String nameOrLink) {
        if (nameOrLink == null) {
            return "";
        }
        String name = nameOrLink.strip();
        int addstickers = name.lastIndexOf("addstickers/");
        if (addstickers >= 0) {
            name = name.substring(addstickers + "addstickers/".length());
        }
        // хвост ссылки вроде ?single и обрамление из скобок или кавычек
        int query = name.indexOf('?');
        if (query >= 0) {
            name = name.substring(0, query);
        }
        return name.replace("@", "").replace("/", "").strip();
    }

    /** Сохранённые и недавние стикеры — то, чем она реально пользуется. */
    public List<TdApi.Sticker> savedStickers(int limit) {
        List<TdApi.Sticker> result = new ArrayList<>();
        try {
            result.addAll(List.of(ask(new TdApi.GetFavoriteStickers()).stickers));
        } catch (RuntimeException e) {
            log.debug("Избранные стикеры недоступны: {}", e.getMessage());
        }
        try {
            TdApi.GetRecentStickers recent = new TdApi.GetRecentStickers();
            for (TdApi.Sticker sticker : ask(recent).stickers) {
                if (result.stream().noneMatch(known -> known.id == sticker.id)) {
                    result.add(sticker);
                }
            }
        } catch (RuntimeException e) {
            log.debug("Недавние стикеры недоступны: {}", e.getMessage());
        }
        result.forEach(this::rememberSticker);
        return result.size() > limit ? result.subList(0, limit) : result;
    }

    /**
     * Стикеры из всех установленных паков: не только те, что она уже кидала
     * (избранные/недавние), а вообще все, что у неё есть. Из каждого пака
     * берётся случайная выборка — иначе огромный пак затопит контекст.
     * <p>
     * Живой человек не листает весь список стикеров перед каждым сообщением,
     * но у него в голове есть общее представление о том, что у него вообще есть.
     * Так и здесь: модель знает, что у неё есть паки, и может открыть любой.
     */
    public List<TdApi.Sticker> allStickersForChoice(int perPack) {
        java.util.Set<Long> seen = new java.util.HashSet<>();
        List<TdApi.Sticker> result = new ArrayList<>();
        // сначала избранные — они самые важные
        try {
            for (TdApi.Sticker sticker : ask(new TdApi.GetFavoriteStickers()).stickers) {
                if (seen.add(sticker.id)) {
                    result.add(sticker);
                }
            }
        } catch (RuntimeException e) {
            log.debug("Избранные стикеры недоступны: {}", e.getMessage());
        }
        // потом недавние
        try {
            for (TdApi.Sticker sticker : ask(new TdApi.GetRecentStickers()).stickers) {
                if (seen.add(sticker.id)) {
                    result.add(sticker);
                }
            }
        } catch (RuntimeException e) {
            log.debug("Недавние стикеры недоступны: {}", e.getMessage());
        }
        // потом по сэмплингу из каждого установленного пака
        for (TdApi.StickerSetInfo info : stickerSets()) {
            try {
                TdApi.StickerSet set = ask(new TdApi.GetStickerSet(info.id));
                if (set.stickers == null || set.stickers.length == 0) {
                    continue;
                }
                List<TdApi.Sticker> pool = new ArrayList<>(List.of(set.stickers));
                java.util.Collections.shuffle(pool, java.util.concurrent.ThreadLocalRandom.current());
                int take = Math.min(perPack, pool.size());
                for (int i = 0; i < take; i++) {
                    TdApi.Sticker s = pool.get(i);
                    if (seen.add(s.id)) {
                        result.add(s);
                    }
                }
            } catch (RuntimeException e) {
                log.debug("Пак {} недоступен для сэмплинга: {}", info.name, e.getMessage());
            }
        }
        result.forEach(this::rememberSticker);
        return result;
    }

    /**
     * Установить пак по идентификатору стикера из чужого сообщения.
     * Возвращает имя пака или пустую строку, если не вышло.
     */
    public String installPackBySticker(long stickerId) {
        java.util.Optional<StickerRef> ref = sticker(stickerId);
        if (ref.isEmpty()) {
            return "";
        }
        TdApi.Sticker sticker;
        try {
            // нужен сам стикер с setId — берём из кэша или запрашиваем
            // StickerRef несёт только fileId/remoteId, поэтому ищем через GetStickerSet
            // по последнему известному setId. Проще: перебрать все паки и найти.
            sticker = null;
            for (TdApi.StickerSetInfo info : stickerSets()) {
                try {
                    TdApi.StickerSet set = ask(new TdApi.GetStickerSet(info.id));
                    for (TdApi.Sticker s : set.stickers) {
                        if (s.id == stickerId) {
                            sticker = s;
                            break;
                        }
                    }
                    if (sticker != null) {
                        break;
                    }
                } catch (RuntimeException ignore) { }
            }
        } catch (RuntimeException e) {
            log.debug("Не удалось найти пак стикера {}: {}", stickerId, e.getMessage());
            return "";
        }
        if (sticker == null || sticker.setId == 0) {
            return "";
        }
        try {
            TdApi.StickerSet set = ask(new TdApi.GetStickerSet(sticker.setId));
            if (!set.isInstalled) {
                installStickerSet(set.id);
            }
            log.info("Пак {} установлен из чужого стикера", set.name);
            return set.name;
        } catch (RuntimeException e) {
            log.debug("Не удалось установить пак по стикеру: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Отправленное сообщение сначала получает временный номер, и только потом
     * Telegram присылает настоящий. Если отдать модели временный, она не сможет
     * ни поправить свою же реплику, ни удалить её: правка уйдёт в никуда.
     */
    public void onSendSucceeded(long temporaryId, TdApi.Message message) {
        delivery.succeeded(temporaryId, message);
    }

    public void onSendFailed(long chatId, long temporaryId, String reason) {
        delivery.failed(chatId, temporaryId, reason);
    }

    public Optional<TdApi.Message> message(long chatId, long messageId) {
        return messageCache.optional(chatId + ":" + messageId, key -> {
            try {
                return Optional.ofNullable(ask(new TdApi.GetMessage(chatId, messageId)));
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        });
    }

    /** Текст правили или сообщение удалили — прежний ответ больше не верен. */
    public void forgetMessage(long chatId, long messageId) {
        messageCache.forget(chatId + ":" + messageId);
    }

    public void typing(long chatId) {
        TdApi.SendChatAction action = new TdApi.SendChatAction();
        action.chatId = chatId;
        action.action = new TdApi.ChatActionTyping();
        throttled().send(action);
    }

    // ──────────────────────── Управление собственным профилем ────────────────────────

    /**
     * Меняет описание профиля («О себе»). Люди обновляют его, когда меняется
     * настроение, появляются новые интересы или просто хочется.
     */
    public boolean setBio(String bio) {
        try {
            perform(new TdApi.SetBio(bio == null ? "" : bio));
            log.info("Обновила описание профиля");
            return true;
        } catch (TelegramDelivery.Unavailable e) {
            log.info("Не удалось обновить описание: Telegram недоступен");
            return false;
        } catch (RuntimeException e) {
            log.debug("Не удалось обновить описание: {}", e.getMessage());
            return false;
        }
    }

    /** Текущее описание профиля. */
    public String myBio() {
        try {
            TdApi.UserFullInfo info = ask(new TdApi.GetUserFullInfo(myId));
            return info.bio == null || info.bio.text == null ? "" : info.bio.text;
        } catch (RuntimeException e) {
            return "";
        }
    }

    /**
     * Меняет имя (first_name / last_name). Люди меняют никнеймы, добавляют
     * эмодзи, убирают фамилию — это нормально.
     */
    public boolean setName(String firstName, String lastName) {
        try {
            perform(new TdApi.SetName(
                    firstName == null ? "" : firstName,
                    lastName == null ? "" : lastName));
            log.info("Обновила имя: {} {}", firstName, lastName);
            return true;
        } catch (TelegramDelivery.Unavailable e) {
            log.info("Не удалось обновить имя: Telegram недоступен");
            return false;
        } catch (RuntimeException e) {
            log.debug("Не удалось обновить имя: {}", e.getMessage());
            return false;
        }
    }

    /** Текущее имя. */
    public String myName() {
        try {
            TdApi.User me = ask(new TdApi.GetMe());
            String first = me.firstName == null ? "" : me.firstName;
            String last = me.lastName == null ? "" : me.lastName;
            return last.isEmpty() ? first : first + " " + last;
        } catch (RuntimeException e) {
            return "";
        }
    }

    /**
     * Ставит фото профиля из локального файла. Загружает через TDLib
     * (InputFileLocal), а не через HTTP — так надёжнее и без лишних зависимостей.
     */
    public boolean setProfilePhoto(Path photoPath) {
        if (!Files.exists(photoPath)) {
            log.debug("Файл аватарки не найден: {}", photoPath);
            return false;
        }
        try {
            TdApi.InputFileLocal file = new TdApi.InputFileLocal(photoPath.toAbsolutePath().toString());
            TdApi.InputChatPhotoStatic photo = new TdApi.InputChatPhotoStatic(file);
            perform(new TdApi.SetProfilePhoto(photo, false));
            log.info("Обновила аватарку: {}", photoPath.getFileName());
            return true;
        } catch (TelegramDelivery.Unavailable e) {
            log.info("Не удалось обновить аватарку: Telegram недоступен");
            return false;
        } catch (RuntimeException e) {
            log.debug("Не удалось обновить аватарку: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Ставит музыку профиля из локального аудиофайла.
     * Загружает через TDLib (InputFileLocal), а не через HTTP.
     */
    public boolean setProfileMusic(Path audioPath, String title, String performer, int duration) {
        if (!Files.exists(audioPath)) {
            log.debug("Аудиофайл не найден: {}", audioPath);
            return false;
        }
        try {
            TdApi.InputFileLocal file = new TdApi.InputFileLocal(audioPath.toAbsolutePath().toString());
            perform(new TdApi.AddProfileAudio(file, duration, title, performer));
            log.info("Обновила музыку профиля: {}", audioPath.getFileName());
            return true;
        } catch (TelegramDelivery.Unavailable e) {
            log.info("Не удалось обновить музыку профиля: Telegram недоступен");
            return false;
        } catch (RuntimeException e) {
            log.debug("Не удалось обновить музыку профиля: {}", e.getMessage());
            return false;
        }
    }

    /** Удаляет музыку профиля по file_id. */
    public boolean removeProfileMusic(int fileId) {
        try {
            perform(new TdApi.RemoveProfileAudio(fileId));
            log.info("Удалила музыку профиля: {}", fileId);
            return true;
        } catch (TelegramDelivery.Unavailable e) {
            return false;
        } catch (RuntimeException e) {
            log.debug("Не удалось удалить музыку профиля: {}", e.getMessage());
            return false;
        }
    }

    /** Список музыки профиля (file_id, title, performer). */
    public List<ProfileAudio> getProfileMusic() {
        try {
            TdApi.Audios audios = ask(new TdApi.GetUserProfileAudios(myId(), 0, 20));
            List<ProfileAudio> result = new ArrayList<>();
            if (audios.audios != null) {
                for (TdApi.Audio audio : audios.audios) {
                    result.add(new ProfileAudio(audio.audio.id, audio.title, audio.performer, audio.duration));
                }
            }
            return result;
        } catch (RuntimeException e) {
            log.debug("Не удалось получить музыку профиля: {}", e.getMessage());
            return List.of();
        }
    }

    public record ProfileAudio(long fileId, String title, String performer, int duration) {}

    /** Удаляет все фото профиля. */
    public boolean deleteAllProfilePhotos() {
        try {
            for (long id : myProfilePhotoIds()) {
                perform(new TdApi.DeleteProfilePhoto(id));
            }
            log.info("Удалила фото профиля");
            return true;
        } catch (TelegramDelivery.Unavailable e) {
            return false;
        } catch (RuntimeException e) {
            log.debug("Не удалось удалить фото профиля: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Список собственных фото профиля (file_id).
     */
    public List<Long> myProfilePhotoIds() {
        try {
            TdApi.User me = ask(new TdApi.GetMe());
            if (me.profilePhoto == null) {
                return List.of();
            }
            // TDLib возвращает только текущее фото в User.profilePhoto.
            // Полный список можно получить через GetUserFullInfo, но для
            // «поставить новую» достаточно знать, есть ли хоть одна.
            return List.of(me.profilePhoto.id);
        } catch (RuntimeException e) {
        return List.of();
    }
}
}
