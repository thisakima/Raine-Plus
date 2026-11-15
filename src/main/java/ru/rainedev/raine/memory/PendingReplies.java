package ru.rainedev.raine.memory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Отслеживает сообщения, на которые собеседники ещё не ответили.
 * <p>
 * Живой человек помнит, кому он написал и не получил ответа, и иногда
 * возвращается к этому: «ой, ты мне так и не ответил про то», «я тут
 * вспомнила». Без этого она выглядит как бот, у которого нет памяти
 * между разговорами.
 * <p>
 * Хранится в памяти и дублируется в файл, чтобы пережить перезапуск.
 */
public final class PendingReplies {

    private static final Logger log = LoggerFactory.getLogger(PendingReplies.class);

    private static final String FORMAT = "# confirmed-v2";
    private final Path file;
    private final java.time.Clock clock;
    private final Map<Long, Entry> pending = new ConcurrentHashMap<>();

    public record Entry(long chatId, String title, long atMillis, String text, boolean reminded) {}

    public PendingReplies(Path file) {
        this(file, java.time.Clock.systemUTC());
    }

    PendingReplies(Path file, java.time.Clock clock) {
        this.file = file;
        this.clock = clock;
        load();
    }

    /**
     * Она отправила текстовое сообщение — запоминаем, что ждём ответ.
     * Голосовые и фото не считаем: на них не всегда отвечают текстом,
     * и отслеживание только запутает.
     */
    public synchronized void markSent(long chatId, String title, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        Entry previous = pending.get(chatId);
        // A follow-up cannot create another follow-up chain without an incoming reply.
        Entry entry = new Entry(chatId, title == null ? "" : title,
                clock.millis(), text.strip(), previous != null && previous.reminded());
        pending.put(chatId, entry);
        persist();
    }

    /**
     * Собеседник написал — долг по ответу закрыт. Вызывается при любом
     * входящем от этого чата, даже если сообщение не адресовано ей:
     * человек видит, что ему ответили, и перестаёт ждать.
     */
    public synchronized void markAnswered(long chatId) {
        if (pending.remove(chatId) != null) {
            persist();
        }
    }

    /**
     * Записи, которым больше {@code olderThanMillis}. Для спонтанных
     * порывов: «кому я написала и мне не ответили?».
     */
    public synchronized List<Entry> stale(long olderThanMillis) {
        long cutoff = clock.millis() - olderThanMillis;
        List<Entry> result = new ArrayList<>();
        for (Entry e : pending.values()) {
            if (!e.reminded() && e.atMillis() <= cutoff) {
                result.add(e);
            }
        }
        // от старых к новым: сначала вспоминается самое давнее
        result.sort((a, b) -> Long.compare(a.atMillis(), b.atMillis()));
        List<Entry> shown = result.stream().limit(3).toList();
        for (Entry e : shown) {
            pending.put(e.chatId(), new Entry(e.chatId(), e.title(), e.atMillis(), e.text(), true));
        }
        if (!shown.isEmpty()) persist();
        return shown;
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            // Legacy entries were based on local acceptance, not confirmed sends.
            if (lines.isEmpty() || !FORMAT.equals(lines.getFirst())) return;
            for (String line : lines.subList(1, lines.size())) {
                String[] parts = line.split("\\|", 5);
                if (parts.length < 5) {
                    continue;
                }
                try {
                    long chatId = Long.parseLong(parts[0]);
                    long at = Long.parseLong(parts[2]);
                    pending.put(chatId, new Entry(chatId, parts[1], at, parts[4], Boolean.parseBoolean(parts[3])));
                } catch (NumberFormatException ignored) {
                    // битая строка — пропускаем, не роняем запуск
                }
            }
            if (!pending.isEmpty()) {
                log.info("Загружено неотвеченных: {}", pending.size());
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
            for (Entry e : pending.values()) {
                // формат: chatId|title|timestamp|text
                // текст может содержать |, поэтому split с limit=4
                out.append(e.chatId()).append('|')
                        .append(e.title().replace("|", "_").replace("\n", " ")).append('|')
                        .append(e.atMillis()).append('|').append(e.reminded()).append('|')
                        .append(e.text().replace("\n", " ")).append('\n');
            }
            ru.rainedev.raine.storage.AtomicFiles.writeString(file, out.toString());
        } catch (IOException e) {
            log.warn("Не удалось сохранить неотвеченные: {}", e.getMessage());
        }
    }
}
