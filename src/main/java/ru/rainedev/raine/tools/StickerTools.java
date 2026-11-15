package ru.rainedev.raine.tools;

import it.tdlight.jni.TdApi;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.core.Tool;
import ru.rainedev.raine.telegram.Telegram;
import ru.rainedev.raine.telegram.TelegramActions;
import ru.rainedev.raine.vision.TelegramMedia;

/**
 * Стикеры. Вынесены отдельно, потому что их три и они самостоятельны:
 * посмотреть свои, забрать понравившийся чужой, отправить.
 */
public final class StickerTools {

    private static final Logger log = LoggerFactory.getLogger(StickerTools.class);

    /** Столько стикеров помещается в избранном и недавних. */
    private static final int LIMIT = 40;

    private final Telegram telegram;
    private final TelegramActions actions;
    private TelegramMedia media;

    /** Отправлять стикер можно ровно туда же, куда и обычное сообщение. */
    private java.util.function.LongPredicate allowed = chatId -> true;

    public StickerTools(Telegram telegram, TelegramActions actions) {
        this.telegram = telegram;
        this.actions = actions;
    }

    public void allowedTo(java.util.function.LongPredicate allowed) {
        this.allowed = allowed;
    }

    public void media(TelegramMedia media) {
        this.media = media;
    }

    /**
     * Список своих стикеров с описанием каждого.
     * <p>
     * Одного эмодзи мало: по нему не выбрать, какой стикер уместен сейчас.
     * Описания берутся зрением и кэшируются, поэтому список стоит дорого
     * только в первый раз.
     */
    /**
     * Сколько стикеров брать из каждого пака при широком списке. Три —
     * разумный компромисс: выбор достаточный, а описание быстрое.
     */
    private static final int PER_PACK = 3;

    /**
     * Описания стикеров кэшируются в памяти: каждый раз прогонять их через
     * модель зрения — это секунды ожидания на каждое сообщение. Vision
     * кэширует на диск, но даже чтение диска и разбор строк ощутимы,
     * когда стикеров десятки.
     */
    private final java.util.Map<Long, String> descriptionCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    public Tool list() {
        return Tool.simple("sticker_list", "Returns your stickers with a description of each — "
                + "a sample from every installed pack plus your favourites and recent ones. "
                + "Use this before #sticker_send to pick one that fits the mood.", arguments -> {
                    // живые люди не выбирают стикер из «последних использованных» —
                    // они в голове держат все свои паки. Так и здесь: сэмпл из каждого
                    List<TdApi.Sticker> stickers = telegram.allStickersForChoice(PER_PACK);
                    if (stickers.isEmpty()) {
                        String packs = packsOfMine();
                        return packs.isEmpty()
                                ? "You have no saved stickers yet."
                                : "You have no stickers yet, but you have packs." + packs;
                    }
                    return describe(stickers) + packsOfMine();
                });
    }

    /**
     * Целый пак по ссылке. Присланный пак — обычный подарок в переписке,
     * и брать из него можно было бы только то, что человек прислал поштучно:
     * поиск по имени открывает весь набор разом.
     */
    public Tool pack() {
        return Tool.named("sticker_pack")
                .describedAs("Opens a whole sticker pack and keeps it: pass a t.me/addstickers/... link "
                        + "or just the pack name. Returns every sticker in it, ready for #sticker_send.")
                .requiredString("name", "Pack link or its short name")
                .build(arguments -> {
                    String name = arguments.path("name").asText("");
                    var found = telegram.stickerSet(name);
                    if (found.isEmpty()) {
                        return "There is no such sticker pack. Check the link or the name.";
                    }
                    TdApi.StickerSet set = found.get();
                    if (!set.isInstalled) {
                        // ставим себе: иначе пак живёт до перезапуска и в Telegram его не видно
                        telegram.installStickerSet(set.id);
                    }
                    log.info("Пак {} ({}) открыт, стикеров: {}", set.name, set.title, set.stickers.length);
                    List<TdApi.Sticker> shown = set.stickers.length > LIMIT
                            ? List.of(set.stickers).subList(0, LIMIT)
                            : List.of(set.stickers);
                    String tail = set.stickers.length > LIMIT
                            ? "\nFirst %d of %d stickers.".formatted(LIMIT, set.stickers.length)
                            : "";
                    return "Pack \"%s\" is yours now.\n%s%s".formatted(set.title, describe(shown), tail);
                });
    }

    /**
     * Список с описаниями. Описания берутся из кэша в памяти, а если стикер
     * ещё не описан — описываются параллельно, чтобы первый вызов не тормозил
     * весь чат на полминуты.
     */
    private String describe(List<TdApi.Sticker> stickers) {
        // те, что уже описаны, отдаются мгновенно; остальные — параллельно
        java.util.Map<Long, String> missing = new java.util.concurrent.ConcurrentHashMap<>();
        List<TdApi.Sticker> toDescribe = new java.util.ArrayList<>();
        for (TdApi.Sticker sticker : stickers) {
            String cached = descriptionCache.get(sticker.id);
            if (cached != null) {
                missing.put(sticker.id, cached);
            } else {
                toDescribe.add(sticker);
            }
        }
        if (!toDescribe.isEmpty() && media != null) {
            try (var pool = java.util.concurrent.Executors.newFixedThreadPool(
                    Math.min(6, toDescribe.size()),
                    Thread.ofVirtual().name("raine-sticker-", 0).factory())) {
                List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
                for (TdApi.Sticker sticker : toDescribe) {
                    futures.add(pool.submit(() -> {
                        String desc = media.describeSticker(sticker);
                        descriptionCache.put(sticker.id, desc == null ? "" : desc);
                    }));
                }
                for (java.util.concurrent.Future<?> f : futures) {
                    try {
                        f.get();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (java.util.concurrent.ExecutionException e) {
                        // один стикер не описался — не повод ронять весь список
                        log.debug("Стикер не описался: {}", e.getMessage());
                    }
                }
            }
        }

        StringBuilder out = new StringBuilder("<stickers>\n");
        for (TdApi.Sticker sticker : stickers) {
            out.append("<sticker sticker_id=\"").append(sticker.id)
                    .append("\" emoji=\"").append(sticker.emoji == null ? "" : sticker.emoji)
                    .append("\">");
            String desc = descriptionCache.getOrDefault(sticker.id, "");
            if (!desc.isBlank()) {
                out.append('\n').append(desc.strip()).append('\n');
            }
            out.append("</sticker>\n");
        }
        return out.append("</stickers>").toString();
    }

    /**
     * Имена своих паков идут следом за стикерами — без них она не знает,
     * что у неё вообще есть, и открывать ей нечего. Описания здесь не нужны:
     * содержимое покажет #sticker_pack, а список должен оставаться дешёвым.
     */
    private String packsOfMine() {
        List<TdApi.StickerSetInfo> sets = telegram.stickerSets();
        if (sets.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("\n<sticker_packs>\n");
        for (TdApi.StickerSetInfo set : sets) {
            out.append("<pack name=\"").append(set.name).append("\" title=\"").append(set.title)
                    .append("\" stickers=\"").append(set.size).append("\" />\n");
        }
        return out.append("</sticker_packs>\nOpen any of them with #sticker_pack.").toString();
    }

    /**
     * Установить себе весь пак, из которого пришёл чужой стикер. Когда
     * собеседник кидает классный стикер, живой человек часто забирает себе
     * весь набор, а не одну штуку.
     */
    public Tool savePack() {
        return Tool.named("sticker_save_pack")
                .describedAs("Installs the whole sticker pack that a received sticker belongs to. "
                        + "Use this when someone sent a sticker you really liked and you want "
                        + "the whole pack, not just one sticker.")
                .requiredInteger("sticker_id", "Id of the sticker whose pack you want to install")
                .build(arguments -> {
                    long stickerId = ru.rainedev.raine.core.Numbers.longAt(arguments, "sticker_id", 0);
                    if (stickerId == 0) {
                        return "Provide the sticker_id.";
                    }
                    String packName = telegram.installPackBySticker(stickerId);
                    if (packName.isEmpty()) {
                        return "Couldn't find the pack for that sticker, or it's already installed.";
                    }
                    log.info("Пак {} установлен по чужому стикеру", packName);
                    return "Pack \"%s\" installed. Use #sticker_pack with this name to see all stickers in it, "
                            .formatted(packName)
                            + "or pick one right away with #sticker_list.";
                });
    }

    /** Понравившийся чужой стикер можно забрать себе. */
    public Tool save() {
        return Tool.named("sticker_save")
                .describedAs("Saves a sticker so you can use it later. Use this if you liked a sticker someone sent.")
                .requiredInteger("sticker_id", "Id of the sticker to save")
                .build(arguments -> {
                    long stickerId = ru.rainedev.raine.core.Numbers.longAt(arguments, "sticker_id", 0);
                    if (stickerId == 0) {
                        return "Provide the sticker_id you want to save.";
                    }
                    java.util.Optional<Telegram.StickerRef> sticker = telegram.sticker(stickerId);
                    if (sticker.isEmpty()) {
                        return "You have not seen that sticker. Use sticker_list, open a pack with "
                                + "sticker_pack, or save one you were sent.";
                    }
                    actions.saveSticker(sticker.get());
                    log.info("Стикер {} сохранён", stickerId);
                    // сохранить молча — странно: человек в такой момент говорит «о, забрала себе»
                    return "Sticker saved. Tell them you liked it and kept it.";
                });
    }

    /** Стикер вместо слов — иногда точнее любого текста. */
    public Tool send(long chatId) {
        return Tool.named("sticker_send")
                .describedAs("Sends one of your saved stickers. Call sticker_list first to see what you have.")
                .requiredInteger("sticker_id", "Id of the sticker to send")
                .optionalString("reply_to_message_id",
                        "Optional. Quote a specific message — a sticker in reply to something said earlier.")
                .build(arguments -> {
                    // круг общения проверяется и здесь: открыть чат — одно,
                    // а отправить в него — другое, и запрет должен работать в обоих
                    if (!allowed.test(chatId)) {
                        log.info("Стикер в чат {} вне круга общения не отправлен", chatId);
                        return "You cannot send anything to that chat.";
                    }
                    long stickerId = ru.rainedev.raine.core.Numbers.longAt(arguments, "sticker_id", 0);
                    // отправить можно всё, что ей известно: избранное, недавнее,
                    // открытый пак, присланный только что стикер. Проверка по одному
                    // избранному отсекала целые паки — а именно ими и пользуются
                    java.util.Optional<Telegram.StickerRef> sticker = telegram.sticker(stickerId);
                    if (sticker.isEmpty()) {
                        return "You don't have this sticker. Call sticker_list to see yours, "
                                + "or sticker_pack to open a pack.";
                    }
                    Long replyTo = ru.rainedev.raine.core.Numbers.longAt(arguments, "reply_to_message_id")
                            .filter(id -> telegram.message(chatId, id).isPresent())
                            .orElse(null);
                    actions.typing(chatId);
                    actions.sendSticker(chatId, sticker.get(), replyTo);
                    log.info("Стикер {} отправлен в чат {}", stickerId, chatId);
                    return "Sticker sent.";
                });
    }
}
