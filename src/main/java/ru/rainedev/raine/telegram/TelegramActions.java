package ru.rainedev.raine.telegram;

import it.tdlight.client.SimpleTelegramClient;
import it.tdlight.jni.TdApi;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Всё, что меняет состояние на стороне Telegram, собрано здесь.
 * <p>
 * Отдельный интерфейс нужен ради безопасного режима: пока аккаунтом
 * пользуется другой клиент, второму нельзя ни писать, ни открывать чаты —
 * открытие пометит сообщения прочитанными на весь аккаунт.
 */
public interface TelegramActions {

    /** @return server-confirmed message ID, or 0 in read-only mode; unconfirmed sends throw. */
    long sendMessage(long chatId, String text, Long replyToMessageId);

    /**
     * Отправляет сообщение с поддержкой premium (custom) эмодзи в тексте.
     * Формат в тексте: :custom:1234567890: — будет заменено на соответствующую
     * сущность customEmoji. Обычный текст остаётся без изменений.
     */
    long sendMessageWithCustomEmojis(long chatId, String text, Long replyToMessageId);

    void openChat(long chatId);

    void closeChat(long chatId);

    /** Снимает «печатает...»: иначе индикатор висит ещё несколько секунд после ухода. */
    void stopTyping(long chatId);

    void markRead(long chatId, long[] messageIds);

    /** Индикатор «печатает…» — тоже видимое действие на аккаунте. */
    void typing(long chatId);

    /** «Записывает голосовое…» — собеседник видит это и ждёт. */
    void recordingVoice(long chatId);

    void react(long chatId, long messageId, String emoji);

    /**
     * Парсит текст на предмет плейсхолдеров premium эмодзи в формате
     * <code>:custom:1234567890:</code> и возвращает отформатированный текст
     * с сущностями для отправки.
     */
    static TdApi.FormattedText parseCustomEmojis(String text) {
        // паттерн: :custom:<id>:
        Pattern pattern = Pattern.compile(":custom:(\\d+):");
        Matcher matcher = pattern.matcher(text);
        List<TdApi.TextEntity> entities = new ArrayList<>();
        StringBuilder cleanText = new StringBuilder();
        int lastEnd = 0;

        while (matcher.find()) {
            // добавляем текст до плейсхолдера
            cleanText.append(text, lastEnd, matcher.start());
            // запоминаем позицию, где вставляем эмодзи
            int offset = cleanText.length();
            cleanText.append(' '); // placeholder символ, entity его перекроет
            long customEmojiId = Long.parseLong(matcher.group(1));
            TdApi.TextEntity entity = new TdApi.TextEntity();
            entity.offset = offset;
            entity.length = 1;
            entity.type = new TdApi.TextEntityTypeCustomEmoji(customEmojiId);
            entities.add(entity);
            lastEnd = matcher.end();
        }
        cleanText.append(text.substring(lastEnd));

        TdApi.FormattedText formatted = new TdApi.FormattedText(cleanText.toString(),
                entities.toArray(new TdApi.TextEntity[0]));
        return formatted;
    }

    /** Все сообщения разом: в Telegram это одна связка, а не несколько пересылок. */
    void forward(long toChatId, long fromChatId, long[] messageIds);

    void deleteMessage(long chatId, long messageId);

    /** Текст правится одним методом, подпись к медиа — другим. */
    void editText(long chatId, long messageId, String text, boolean isMedia);

    void blockAndLeave(long chatId);

    /** Подписка на канал или вход в группу. Результат — честный, с причиной. */
    Telegram.JoinOutcome joinChat(long chatId);

    /** То же по ссылке t.me/... — публичный ник или приглашение из bio. */
    Telegram.JoinOutcome joinChatByLink(String link);

    void sendSticker(long chatId, Telegram.StickerRef sticker, Long replyToMessageId);

    void saveSticker(Telegram.StickerRef sticker);

    void saveContact(long userId, String firstName, String lastName);

    void sendVoice(long chatId, java.nio.file.Path file, int seconds);

    void sendPhoto(long chatId, java.nio.file.Path file, String caption);

    /** Видео в чат — например, смешной ролик из TikTok подруге. */
    void sendVideo(long chatId, java.nio.file.Path file, String caption);

    /** Гифка в чат — из сохранённых. */
    void sendAnimation(long chatId, Telegram.AnimationRef animation, String caption);

    /** Опрос. Люди в каналах и группах их постят постоянно. */
    void sendPoll(long chatId, String question, java.util.List<String> options);

    /** Ответ на чужую историю — уходит в личку автору. */
    void replyToStory(long chatId, int storyId, String text);

    /** Проголосовать в опросе. Люди голосуют в чужих опросах постоянно. */
    void votePoll(long chatId, long messageId, int optionId);

    void uploadingPhoto(long chatId);

    /** «Загружает видео…» — видно, пока ролик уходит в чат. */
    void uploadingVideo(long chatId);

    /** Статус «в сети». По нему собеседник видит, спит она или нет. */
    void setOnline(boolean online);

    /** Действует по-настоящему. */
    final class Live implements TelegramActions {

        private static final Logger log = LoggerFactory.getLogger(Live.class);

        private final SimpleTelegramClient client;
        private final Telegram telegram;
        private final Throttle throttle = new Throttle();

        /** Спросить и дождаться ответа, но не бесконечно. */
        private <T extends TdApi.Object> T ask(TdApi.Function<T> request) {
            if (request instanceof TdApi.GetChat) {
                return Await.reply(throttled().send(request), request.getClass().getSimpleName());
            }
            return telegram.perform(request);
        }

        /** Каждое действие тоже считается обращением к Telegram. */
        private SimpleTelegramClient throttled() {
            throttle.allow();
            return client;
        }

        public Live(SimpleTelegramClient client, Telegram telegram) {
            this.client = client;
            this.telegram = telegram;
        }

        @Override
        public long sendMessage(long chatId, String text, Long replyToMessageId) {
            TdApi.InputMessageText content = new TdApi.InputMessageText();
            content.text = new TdApi.FormattedText(text, new TdApi.TextEntity[0]);

            TdApi.SendMessage request = new TdApi.SendMessage();
            request.chatId = chatId;
            request.inputMessageContent = content;
            if (replyToMessageId != null) {
                TdApi.InputMessageReplyToMessage replyTo = new TdApi.InputMessageReplyToMessage();
                replyTo.messageId = replyToMessageId;
                request.replyTo = replyTo;
            }
            return ask(request).id;
        }

        @Override
        public long sendMessageWithCustomEmojis(long chatId, String text, Long replyToMessageId) {
            TdApi.InputMessageText content = new TdApi.InputMessageText();
            content.text = parseCustomEmojis(text);

            TdApi.SendMessage request = new TdApi.SendMessage();
            request.chatId = chatId;
            request.inputMessageContent = content;
            if (replyToMessageId != null) {
                TdApi.InputMessageReplyToMessage replyTo = new TdApi.InputMessageReplyToMessage();
                replyTo.messageId = replyToMessageId;
                request.replyTo = replyTo;
            }
            return ask(request).id;
        }

        @Override
        public void openChat(long chatId) {
            telegram.delivery().requireReady();
            telegram.transientAction(new TdApi.OpenChat(chatId));
        }

        @Override
        public void closeChat(long chatId) {
            telegram.transientAction(new TdApi.CloseChat(chatId));
        }

        @Override
        public void stopTyping(long chatId) {
            TdApi.SendChatAction action = new TdApi.SendChatAction();
            action.chatId = chatId;
            action.action = new TdApi.ChatActionCancel();
            telegram.transientAction(action);
        }

        @Override
        public void markRead(long chatId, long[] messageIds) {
            telegram.transientAction(new TdApi.ViewMessages(chatId, messageIds, null, false));
        }

        @Override
        public void typing(long chatId) {
            TdApi.SendChatAction action = new TdApi.SendChatAction();
            action.chatId = chatId;
            action.action = new TdApi.ChatActionTyping();
            telegram.transientAction(action);
        }

        @Override
        public void recordingVoice(long chatId) {
            TdApi.SendChatAction action = new TdApi.SendChatAction();
            action.chatId = chatId;
            action.action = new TdApi.ChatActionRecordingVoiceNote();
            telegram.transientAction(action);
        }


        @Override
        public void react(long chatId, long messageId, String emoji) {
            telegram.reactWith(chatId, messageId, emoji);
        }

        @Override
        public void forward(long toChatId, long fromChatId, long[] messageIds) {
            TdApi.ForwardMessages request = new TdApi.ForwardMessages();
            request.chatId = toChatId;
            request.fromChatId = fromChatId;
            request.messageIds = messageIds;
            // именно пересылка, с указанием источника, а не копия чужого текста
            // от своего имени
            request.sendCopy = false;
            request.removeCaption = false;
            ask(request);
        }

        @Override
        public void deleteMessage(long chatId, long messageId) {
            TdApi.DeleteMessages request = new TdApi.DeleteMessages();
            request.chatId = chatId;
            request.messageIds = new long[] {messageId};
            request.revoke = true;
            ask(request);
        }

        @Override
        public void editText(long chatId, long messageId, String text, boolean isMedia) {
            TdApi.FormattedText formatted = new TdApi.FormattedText(text, new TdApi.TextEntity[0]);
            if (isMedia) {
                // подпись к фото или видео правится своим методом, иначе TDLib отказывает
                TdApi.EditMessageCaption request = new TdApi.EditMessageCaption();
                request.chatId = chatId;
                request.messageId = messageId;
                request.caption = formatted;
                ask(request);
                return;
            }
            TdApi.InputMessageText content = new TdApi.InputMessageText();
            content.text = formatted;
            TdApi.EditMessageText request = new TdApi.EditMessageText();
            request.chatId = chatId;
            request.messageId = messageId;
            request.inputMessageContent = content;
            ask(request);
        }

        @Override
        public void sendSticker(long chatId, Telegram.StickerRef sticker, Long replyToMessageId) {
            TdApi.InputMessageSticker content = new TdApi.InputMessageSticker();
            // по удалённому идентификатору, а не по локальному: локальный привязан
            // к сессии и после перезапуска указывает в пустоту
            content.sticker = sticker.remoteId().isEmpty()
                    ? new TdApi.InputFileId(sticker.fileId())
                    : new TdApi.InputFileRemote(sticker.remoteId());
            content.emoji = sticker.emoji();
            content.width = sticker.width();
            content.height = sticker.height();

            TdApi.SendMessage request = new TdApi.SendMessage();
            request.chatId = chatId;
            request.inputMessageContent = content;
            if (replyToMessageId != null) {
                TdApi.InputMessageReplyToMessage replyTo = new TdApi.InputMessageReplyToMessage();
                replyTo.messageId = replyToMessageId;
                request.replyTo = replyTo;
            }
            ask(request);
        }

        @Override
        public void saveSticker(Telegram.StickerRef sticker) {
            TdApi.AddFavoriteSticker request = new TdApi.AddFavoriteSticker();
            request.sticker = sticker.remoteId().isEmpty()
                    ? new TdApi.InputFileId(sticker.fileId())
                    : new TdApi.InputFileRemote(sticker.remoteId());
            ask(request);
        }

        @Override
        public void saveContact(long userId, String firstName, String lastName) {
            telegram.addContact(userId, firstName, lastName);
        }

        @Override
        public void sendPhoto(long chatId, java.nio.file.Path file, String caption) {
            TdApi.InputPhoto photo = new TdApi.InputPhoto();
            photo.photo = new TdApi.InputFileLocal(file.toAbsolutePath().toString());

            TdApi.InputMessagePhoto content = new TdApi.InputMessagePhoto();
            content.photo = photo;
            if (caption != null && !caption.isBlank()) {
                content.caption = new TdApi.FormattedText(caption, new TdApi.TextEntity[0]);
            }

            TdApi.SendMessage request = new TdApi.SendMessage();
            request.chatId = chatId;
            request.inputMessageContent = content;
            ask(request);
        }

        @Override
        public void sendVideo(long chatId, java.nio.file.Path file, String caption) {
            TdApi.InputVideo video = new TdApi.InputVideo();
            video.video = new TdApi.InputFileLocal(file.toAbsolutePath().toString());
            video.addedStickerFileIds = new int[0];
            // ширину, высоту и длительность Telegram определит сам по файлу
            video.supportsStreaming = true;

            TdApi.InputMessageVideo content = new TdApi.InputMessageVideo();
            content.video = video;
            if (caption != null && !caption.isBlank()) {
                content.caption = new TdApi.FormattedText(caption, new TdApi.TextEntity[0]);
            }

            TdApi.SendMessage request = new TdApi.SendMessage();
            request.chatId = chatId;
            request.inputMessageContent = content;
            ask(request);
        }

        @Override
        public void sendPoll(long chatId, String question, java.util.List<String> options) {
            TdApi.InputMessagePoll content = new TdApi.InputMessagePoll();
            content.question = new TdApi.FormattedText(question, new TdApi.TextEntity[0]);
            content.options = new TdApi.InputPollOption[options.size()];
            for (int i = 0; i < options.size(); i++) {
                content.options[i] = new TdApi.InputPollOption(
                        new TdApi.FormattedText(options.get(i), new TdApi.TextEntity[0]), null);
            }
            content.isAnonymous = false;
            content.allowsMultipleAnswers = false;

            TdApi.SendMessage pollRequest = new TdApi.SendMessage();
            pollRequest.chatId = chatId;
            pollRequest.inputMessageContent = content;
            ask(pollRequest);
        }

        @Override
        public void replyToStory(long chatId, int storyId, String text) {
            TdApi.InputMessageText content = new TdApi.InputMessageText();
            content.text = new TdApi.FormattedText(text, new TdApi.TextEntity[0]);

            TdApi.SendMessage request = new TdApi.SendMessage();
            request.chatId = chatId;
            request.replyTo = new TdApi.InputMessageReplyToStory(chatId, storyId);
            request.inputMessageContent = content;
            ask(request);
        }

        @Override
        public void votePoll(long chatId, long messageId, int optionId) {
            TdApi.SetPollAnswer request = new TdApi.SetPollAnswer();
            request.chatId = chatId;
            request.messageId = messageId;
            request.optionIds = new int[] {optionId};
            ask(request);
        }

        @Override
        public void setOnline(boolean online) {
            TdApi.SetOption request = new TdApi.SetOption();
            request.name = "online";
            request.value = new TdApi.OptionValueBoolean(online);
            telegram.transientAction(request);
        }

        @Override
        public void uploadingPhoto(long chatId) {
            TdApi.SendChatAction action = new TdApi.SendChatAction();
            action.chatId = chatId;
            action.action = new TdApi.ChatActionUploadingPhoto();
            telegram.transientAction(action);
        }

        @Override
        public void uploadingVideo(long chatId) {
            TdApi.SendChatAction action = new TdApi.SendChatAction();
            action.chatId = chatId;
            action.action = new TdApi.ChatActionUploadingVideo();
            telegram.transientAction(action);
        }

        @Override
        public void sendVoice(long chatId, java.nio.file.Path file, int seconds) {
            TdApi.InputMessageVoiceNote content = new TdApi.InputMessageVoiceNote();
            content.voiceNote = new TdApi.InputFileLocal(file.toAbsolutePath().toString());
            content.duration = seconds;

            TdApi.SendMessage request = new TdApi.SendMessage();
            request.chatId = chatId;
            request.inputMessageContent = content;
            ask(request);
        }

        @Override
        public void sendAnimation(long chatId, Telegram.AnimationRef animation, String caption) {
            TdApi.InputMessageAnimation content = new TdApi.InputMessageAnimation();
            content.animation = animation.remoteId().isEmpty()
                    ? new TdApi.InputAnimation(new TdApi.InputFileId(animation.fileId()), null, null,
                            animation.duration(), animation.width(), animation.height())
                    : new TdApi.InputAnimation(new TdApi.InputFileRemote(animation.remoteId()), null, null,
                            animation.duration(), animation.width(), animation.height());
            if (caption != null && !caption.isBlank()) {
                content.caption = new TdApi.FormattedText(caption, new TdApi.TextEntity[0]);
            }
            TdApi.SendMessage request = new TdApi.SendMessage();
            request.chatId = chatId;
            request.inputMessageContent = content;
            ask(request);
        }

        @Override
        public void blockAndLeave(long chatId) {
            TdApi.Chat chat = ask(new TdApi.GetChat(chatId));

            TdApi.SetMessageSenderBlockList request = new TdApi.SetMessageSenderBlockList();
            // в личке блокируют человека, а не чат: с типом чата запрос проходит,
            // но никого не блокирует
            request.senderId = chat.type instanceof TdApi.ChatTypePrivate priv
                    ? new TdApi.MessageSenderUser(priv.userId)
                    : new TdApi.MessageSenderChat(chatId);
            request.blockList = new TdApi.BlockListMain();
            ask(request);

            // из группы и канала просто уходят
            if (!(chat.type instanceof TdApi.ChatTypePrivate)) {
                try {
                    ask(new TdApi.LeaveChat(chatId));
                } catch (RuntimeException e) {
                    log.debug("Не удалось выйти из чата {}: {}", chatId, e.getMessage());
                }
            }
        }

        @Override
        public Telegram.JoinOutcome joinChat(long chatId) {
            Telegram.JoinOutcome outcome = telegram.joinChatDetailed(chatId);
            if (outcome.joined()) {
                log.info("Подписалась на чат {}", chatId);
            }
            return outcome;
        }

        @Override
        public Telegram.JoinOutcome joinChatByLink(String link) {
            Telegram.JoinOutcome outcome = telegram.joinByLink(link);
            if (outcome.joined()) {
                log.info("Подписалась по ссылке {}", link);
            }
            return outcome;
        }
    }


    /** Ничего не делает, только пишет в лог, что сделала бы. */
    final class ReadOnly implements TelegramActions {

        private static final Logger log = LoggerFactory.getLogger(ReadOnly.class);

        @Override
        public long sendMessage(long chatId, String text, Long replyToMessageId) {
            log.info("[только чтение] в чат {} ушло бы: {}", chatId, text);
            return 0;
        }

        @Override
        public long sendMessageWithCustomEmojis(long chatId, String text, Long replyToMessageId) {
            log.info("[только чтение] в чат {} ушло бы (с premium эмодзи): {}", chatId, text);
            return 0;
        }

        @Override
        public void openChat(long chatId) {
            log.info("[только чтение] чат {} не открываю — это пометит сообщения прочитанными", chatId);
        }

        @Override
        public void closeChat(long chatId) {
            // закрывать нечего
        }

        @Override
        public void stopTyping(long chatId) {
            // индикатор и не показывался
        }

        @Override
        public void markRead(long chatId, long[] messageIds) {
            log.info("[только чтение] {} сообщений в чате {} остаются непрочитанными", messageIds.length, chatId);
        }

        @Override
        public void typing(long chatId) {
            // «печатает…» увидел бы собеседник — в безопасном режиме молчим
        }

        @Override
        public void recordingVoice(long chatId) {
            // индикатор увидел бы собеседник — в безопасном режиме молчим
        }

        @Override
        public void react(long chatId, long messageId, String emoji) {
            log.info("[только чтение] на сообщение {} в чате {} ушла бы реакция {}", messageId, chatId, emoji);
        }

        @Override
        public void forward(long toChatId, long fromChatId, long[] messageIds) {
            log.info("[только чтение] {} сообщений переслалось бы в чат {}", messageIds.length, toChatId);
        }

        @Override
        public void deleteMessage(long chatId, long messageId) {
            log.info("[только чтение] сообщение {} в чате {} удалилось бы", messageId, chatId);
        }

        @Override
        public void editText(long chatId, long messageId, String text, boolean isMedia) {
            log.info("[только чтение] сообщение {} стало бы: {}", messageId, text);
        }

        @Override
        public void sendSticker(long chatId, Telegram.StickerRef sticker, Long replyToMessageId) {
            log.info("[только чтение] в чат {} ушёл бы стикер {}", chatId, sticker.emoji());
        }

        @Override
        public void saveSticker(Telegram.StickerRef sticker) {
            log.info("[только чтение] стикер {} сохранился бы в избранное", sticker.fileId());
        }

        @Override
        public void saveContact(long userId, String firstName, String lastName) {
            log.info("[только чтение] в контакты записался бы {} ({})", firstName, userId);
        }

        @Override
        public void sendVoice(long chatId, java.nio.file.Path file, int seconds) {
            log.info("[только чтение] в чат {} ушло бы голосовое на {} с: {}", chatId, seconds, file);
        }

        @Override
        public void sendPhoto(long chatId, java.nio.file.Path file, String caption) {
            log.info("[только чтение] в чат {} ушло бы фото {} с подписью: {}", chatId, file, caption);
        }

        @Override
        public void sendVideo(long chatId, java.nio.file.Path file, String caption) {
            log.info("[только чтение] в чат {} ушло бы видео {} с подписью: {}", chatId, file, caption);
        }

        @Override
        public void sendPoll(long chatId, String question, java.util.List<String> options) {
            log.info("[только чтение] в чат {} ушёл бы опрос: {} / {}", chatId, question, options);
        }

        @Override
        public void replyToStory(long chatId, int storyId, String text) {
            log.info("[только чтение] ответ на историю {} в чате {}: {}", storyId, chatId, text);
        }

        @Override
        public void votePoll(long chatId, long messageId, int optionId) {
            log.info("[только чтение] в чате {} в опросе {} был бы голос за вариант {}",
                    chatId, messageId, optionId);
        }

        @Override
        public void uploadingPhoto(long chatId) {
            // индикатор увидел бы собеседник — в безопасном режиме молчим
        }

        @Override
        public void uploadingVideo(long chatId) {
            // индикатор увидел бы собеседник — в безопасном режиме молчим
        }

        @Override
        public void sendAnimation(long chatId, Telegram.AnimationRef animation, String caption) {
            log.info("[только чтение] в чат {} ушла бы гифка {} с подписью: {}", chatId, animation.fileId(), caption);
        }

        @Override
        public void blockAndLeave(long chatId) {
            log.info("[только чтение] в чат {} ушёл бы блок и выход", chatId);
        }

        @Override
        public void setOnline(boolean online) {
            log.info("[только чтение] статус онлайн сменился бы на {}", online);
        }

        @Override
        public Telegram.JoinOutcome joinChat(long chatId) {
            log.info("[только чтение] подписалась бы на чат {}", chatId);
            return new Telegram.JoinOutcome(false, "read-only mode");
        }

        @Override
        public Telegram.JoinOutcome joinChatByLink(String link) {
            log.info("[только чтение] подписалась бы по ссылке {}", link);
            return new Telegram.JoinOutcome(false, "read-only mode");
        }
    }
}
