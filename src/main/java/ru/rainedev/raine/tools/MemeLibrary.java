package ru.rainedev.raine.tools;

import it.tdlight.jni.TdApi;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.telegram.Telegram;

/**
 * Коробка с мемами: картинки и гифки, которые она где-то увидела и сохранила,
 * чтобы потом кинуть друзьям.
 * <p>
 * Живой человек не форвардит мемы только в момент, когда увидел: он их
 * складывает и кидает потом, к месту. Без этого она может лишь форвардить
 * из того чата, который открыт прямо сейчас.
 * <p>
 * Что сохранять — решает ОНА. Код не отбирает «смешное» сам: он только
 * кладёт файл и хранит описание, чтобы позже можно было найти нужный.
 */
public final class MemeLibrary {

    private static final Logger log = LoggerFactory.getLogger(MemeLibrary.class);

    /** Больше уже тяжело листать и дорого хранить. */
    private static final int MAX_MEMES = 120;

    private final Path dir;
    private final Path index;
    private final Telegram telegram;
    private final java.util.function.Function<Path, String> describer;

    /** filename -> описание. Порядок важен: старые вытесняются первыми. */
    private final Map<String, String> entries = new LinkedHashMap<>();

    public MemeLibrary(Path dir, Telegram telegram, java.util.function.Function<Path, String> describer) {
        this.dir = dir;
        this.index = dir.resolve("index.txt");
        this.telegram = telegram;
        this.describer = describer;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.warn("Не удалось создать папку мемов: {}", e.getMessage());
        }
        load();
    }

    /**
     * Сохраняет вложение сообщения в коробку.
     *
     * @return имя файла или пустая строка, если сохранить не вышло
     */
    public String save(long chatId, long messageId) {
        TdApi.Message message = telegram.message(chatId, messageId).orElse(null);
        if (message == null) {
            return "";
        }
        Integer fileId = mediaFileId(message.content);
        if (fileId == null) {
            return "";
        }
        Path downloaded = telegram.download(fileId).orElse(null);
        if (downloaded == null || !Files.exists(downloaded)) {
            return "";
        }
        String extension = extensionOf(downloaded, message.content);
        String name = System.currentTimeMillis() + extension;
        try {
            Path target = dir.resolve(name);
            Files.copy(downloaded, target, StandardCopyOption.REPLACE_EXISTING);
            String description = "";
            try {
                description = describer.apply(target);
            } catch (RuntimeException e) {
                log.debug("Не удалось описать мем: {}", e.getMessage());
            }
            synchronized (entries) {
                entries.put(name, description.strip());
                while (entries.size() > MAX_MEMES) {
                    String oldest = entries.keySet().iterator().next();
                    entries.remove(oldest);
                    try {
                        Files.deleteIfExists(dir.resolve(oldest));
                    } catch (IOException ignored) { }
                }
            }
            persist();
            log.info("Мем сохранён: {}", name);
            return name;
        } catch (IOException e) {
            log.warn("Не удалось сохранить мем: {}", e.getMessage());
            return "";
        }
    }

    /** Список коробки с описаниями — по ним она выбирает, что кинуть. */
    public String list() {
        List<Map.Entry<String, String>> snapshot;
        synchronized (entries) {
            snapshot = new ArrayList<>(entries.entrySet());
        }
        if (snapshot.isEmpty()) {
            return "Your meme box is empty. Save something with #save_meme when you see a funny "
                    + "picture or gif in a channel or chat.";
        }
        // свежие сверху: их чаще всего и кидают
        java.util.Collections.reverse(snapshot);
        StringBuilder out = new StringBuilder("<meme_box count=\"")
                .append(snapshot.size()).append("\">\n");
        for (var e : snapshot) {
            out.append("<meme filename=\"").append(e.getKey()).append('"');
            String desc = e.getValue();
            if (desc == null || desc.isBlank()) {
                out.append(" />\n");
            } else {
                String oneLine = desc.replace('\n', ' ').strip();
                if (oneLine.length() > 200) {
                    oneLine = oneLine.substring(0, 200) + "…";
                }
                out.append('>').append(oneLine).append("</meme>\n");
            }
        }
        return out.append("</meme_box>\nSend one with #send_telegram_message using photo_filename.").toString();
    }

    public Path resolve(String filename) {
        Path p = dir.resolve(filename);
        return Files.exists(p) ? p : null;
    }

    public boolean isEmpty() {
        synchronized (entries) {
            return entries.isEmpty();
        }
    }

    /** Какой файл сообщения считать картинкой/гифкой, пригодной для мемов. */
    private static Integer mediaFileId(TdApi.MessageContent content) {
        if (content instanceof TdApi.MessagePhoto photo && photo.photo != null
                && photo.photo.sizes != null && photo.photo.sizes.length > 0) {
            return photo.photo.sizes[photo.photo.sizes.length - 1].photo.id;
        }
        if (content instanceof TdApi.MessageAnimation anim && anim.animation != null) {
            return anim.animation.thumbnail != null ? anim.animation.thumbnail.file.id : null;
        }
        if (content instanceof TdApi.MessageSticker sticker && sticker.sticker != null) {
            return sticker.sticker.sticker.id;
        }
        return null;
    }

    private static String extensionOf(Path file, TdApi.MessageContent content) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) {
            return name.substring(dot);
        }
        if (content instanceof TdApi.MessageAnimation) {
            return ".mp4";
        }
        return ".jpg";
    }

    private void load() {
        if (!Files.exists(index)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(index, StandardCharsets.UTF_8)) {
                int sep = line.indexOf('\t');
                if (sep <= 0) {
                    continue;
                }
                String name = line.substring(0, sep);
                String desc = line.substring(sep + 1);
                if (Files.exists(dir.resolve(name))) {
                    entries.put(name, desc);
                }
            }
            log.info("Коробка мемов: {} шт.", entries.size());
        } catch (IOException e) {
            log.warn("Не удалось прочитать индекс мемов: {}", e.getMessage());
        }
    }

    private void persist() {
        try {
            StringBuilder out = new StringBuilder();
            synchronized (entries) {
                for (var e : entries.entrySet()) {
                    out.append(e.getKey()).append('\t')
                            .append(e.getValue().replace('\n', ' ').replace('\t', ' '))
                            .append('\n');
                }
            }
            Files.writeString(index, out.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Не удалось сохранить индекс мемов: {}", e.getMessage());
        }
    }
}
