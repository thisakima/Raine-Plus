package ru.rainedev.raine.memory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Отложенные ответы: она пообещала написать позже («щас», «на паре, потом
 * напишу», «через минуту») — и реально возвращается.
 * <p>
 * Без этого «щас» звучит как пустая фраза: она сказала и забыла. Живой
 * человек помнит обещания и выполняет их через 20–90 минут.
 * <p>
 * Хранится в памяти и дублируется в файл, чтобы пережить перезапуск.
 */
public final class DeferredReplies {

    private static final Logger log = LoggerFactory.getLogger(DeferredReplies.class);

    private static final String FORMAT = "# confirmed-v2";
    private static final java.util.regex.Pattern PROMISE = java.util.regex.Pattern.compile(
            "(?iuU)\\b(?:напишу|отвечу|расскажу|скину|пришлю|вернусь)\\b");
    private static final java.util.regex.Pattern LATER = java.util.regex.Pattern.compile(
            "(?iuU)\\b(?:потом|позже|скоро|щас|сейчас|вечером|завтра|через|после)\\b");
    private final Path file;
    private final java.time.Clock clock;
    private final Map<Long, Entry> deferred = new ConcurrentHashMap<>();

    public record Entry(long chatId, String title, long atMillis, String trigger, boolean reminded) {}

    public DeferredReplies(Path file) {
        this(file, java.time.Clock.systemUTC());
    }

    DeferredReplies(Path file, java.time.Clock clock) {
        this.file = file;
        this.clock = clock;
        load();
    }

    /**
     * Проверяет последнее отправленное сообщение на признаки отложенного
     * ответа. Если находит — запоминает чат и время, чтобы позже
     * напомнить ей вернуться.
     */
    public synchronized void checkLastSent(long chatId, String title, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        String low = text.toLowerCase(java.util.Locale.ROOT).strip();
        if (low.startsWith("*")) return;
        boolean isDeferred = PROMISE.matcher(low).find() && LATER.matcher(low).find()
                && !low.matches("(?sU).*\\bне\\s+(?:напишу|отвечу|расскажу|скину|пришлю|вернусь)\\b.*");
        if (!isDeferred) {
            done(chatId);
            return;
        }
        // не запоминаем повторно для того же чата, пока предыдущее не сработало
        if (deferred.containsKey(chatId)) {
            return;
        }
        deferred.put(chatId, new Entry(chatId,
                title == null ? "" : title,
                clock.millis(),
                text.strip(), false));
        persist();
        log.info("Отложенный ответ запомнен для чата {}: {}", title, low);
    }

    /**
     * Собеседник написал — значит контекст изменился, отложенный ответ
     * больше не нужен (она ответит в рамках нового разговора).
     */
    public synchronized void cancelIfAnswered(long chatId) {
        if (deferred.remove(chatId) != null) {
            persist();
        }
    }

    /**
     * Записи, которым пора возвращаться: прошло достаточно времени,
     * чтобы «щас» стало реальным действием, а не мгновенной реакцией.
     *
     * @param minAgeMillis минимальный возраст записи (обычно 20-90 мин)
     */
    public synchronized List<Entry> due(long minAgeMillis) {
        long cutoff = clock.millis() - minAgeMillis;
        List<Entry> result = new ArrayList<>();
        for (Entry e : deferred.values()) {
            if (!e.reminded() && e.atMillis() <= cutoff) {
                result.add(e);
            }
        }
        result.sort((a, b) -> Long.compare(a.atMillis(), b.atMillis()));
        List<Entry> shown = result.stream().limit(3).toList();
        for (Entry e : shown) {
            deferred.put(e.chatId(), new Entry(e.chatId(), e.title(), e.atMillis(), e.trigger(), true));
        }
        if (!shown.isEmpty()) persist();
        return shown;
    }

    /** Убирает запись после того, как она была обработана. */
    public synchronized void done(long chatId) {
        if (deferred.remove(chatId) != null) {
            persist();
        }
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            // Unverified promises from older versions must not trigger new messages.
            if (lines.isEmpty() || !FORMAT.equals(lines.getFirst())) return;
            for (String line : lines.subList(1, lines.size())) {
                String[] parts = line.split("\\|", 5);
                if (parts.length < 5) continue;
                try {
                    long chatId = Long.parseLong(parts[0]);
                    long at = Long.parseLong(parts[2]);
                    deferred.put(chatId, new Entry(chatId, parts[1], at, parts[4], Boolean.parseBoolean(parts[3])));
                } catch (NumberFormatException ignored) { }
            }
            if (!deferred.isEmpty()) {
                log.info("Загружено отложенных ответов: {}", deferred.size());
            }
        } catch (IOException e) {
            log.warn("Не удалось прочитать {}: {}", file, e.getMessage());
        }
    }

    private void persist() {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            StringBuilder out = new StringBuilder(FORMAT).append('\n');
            for (Entry e : deferred.values()) {
                out.append(e.chatId()).append('|')
                        .append(e.title().replace("|", "_").replace("\n", " ")).append('|')
                        .append(e.atMillis()).append('|').append(e.reminded()).append('|')
                        .append(e.trigger().replace("\n", " ")).append('\n');
            }
            ru.rainedev.raine.storage.AtomicFiles.writeString(file, out.toString());
        } catch (IOException e) {
            log.warn("Не удалось сохранить отложенные ответы: {}", e.getMessage());
        }
    }
}
