package ru.rainedev.raine.phone;

import it.tdlight.jni.TdApi;

/**
 * Текст уведомления — то, что Raine видит, не открывая чат.
 * <p>
 * Содержимого сообщения здесь намеренно нет: как на экране блокировки,
 * видно только от кого пришло. Чтобы прочитать, нужно открыть чат.
 */
public final class Notifications {

    private Notifications() {}

    /** Открывающий тег: по нему же уведомление ищут, когда чат уже открыт и оно устарело. */
    public static String openingTag(long chatId) {
        return "<notification chat_id=\"%d\">\n".formatted(chatId);
    }

    public static String directMessage(TdApi.Chat chat) {
        return wrap(chat.id, "You received a direct message from %s (chat_id = %d)".formatted(chat.title, chat.id));
    }

    public static String groupMessage(TdApi.Chat chat, String senderName, long senderId) {
        return wrap(chat.id, "%s (user_id = %d) sent a message in group chat \"%s\" (chat_id = %d)"
                .formatted(senderName, senderId, chat.title, chat.id));
    }

    public static String channelPost(TdApi.Chat chat) {
        return wrap(chat.id, "Channel \"%s\" (chat_id=%d) created a new post".formatted(chat.title, chat.id));
    }

    /**
     * «Тебе кинули ролик в TikTok». В отличие от обычного уведомления, ссылка и
     * инструкция уже здесь: она открывает ролик и реагирует в самом TikTok, а не
     * в чате, поэтому строки про «открой чат» намеренно нет.
     */
    public static String tiktokReaction(TdApi.Chat chat, String senderName, String url, String messageText) {
        StringBuilder body = new StringBuilder();
        body.append("%s sent you a TikTok video in your DMs: %s".formatted(senderName, url));
        if (messageText != null && !messageText.isBlank()) {
            body.append("\nTheir message: ").append(messageText.strip());
        }
        body.append("\nOpen it with #tiktok_open_video, then really watch it with #tiktok_watch. ")
                .append("If you genuinely like it, react right there in TikTok — like, comment or repost, ")
                .append("in your own voice; if it's not your thing, just watch and move on. ")
                .append("For these you don't answer in the chat — once you've reacted, end the turn with #wait.");
        return openingTag(chat.id) + body + "\n</notification>\n";
    }

    private static String wrap(long chatId, String body) {
        return openingTag(chatId) + body + "\n</notification>\n"
                + "You don't have any chat open. Use #open tool to open the chat";
    }
}
