package ru.rainedev.raine.memory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Личный дневник — в отличие от {@link Diary}, это не память для поиска,
 * а настоящее письмо от первого лица, по дням.
 * <p>
 * Формат — обычный markdown, один файл на день ({@code 2026-11-11.md}).
 * Без эмбеддингов: человек не ищет в своём дневнике «что-то похожее на
 * запрос», он его перечитывает по датам — с конца, свежее zuerst.
 * <p>
 * Записи сюда пишутся раз в день вечером: что чувствовала, что произошло,
 * с кем общалась, о чём думала. Из этого же материала потом берутся посты
 * в канал, подписи к историям, темы для разговоров «а помнишь вчера».
 */
public final class Journal {

    private static final Logger log = LoggerFactory.getLogger(Journal.class);

    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;

    private final Path dir;

    public Journal(Path dir) {
        this.dir = dir;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось создать каталог дневника " + dir, e);
        }
    }

    public Path directory() {
        return dir;
    }

    /** Запись дня: дата и сам текст. */
    public record Page(LocalDate date, String text) {}

    /**
     * Сегодняшняя страница. Пустая, если ещё не писалось.
     */
    public Optional<Page> today() {
        return read(LocalDate.now());
    }

    public Optional<Page> read(LocalDate date) {
        Path file = dir.resolve(DAY.format(date) + ".md");
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            return Optional.of(new Page(date, text));
        } catch (IOException e) {
            log.warn("Не удалось прочитать страницу дневника {}: {}", date, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Дописывает к сегодняшней странице. Если страницы ещё нет — создаёт.
     * Одна страница может содержать несколько разделов за день: утро, день,
     * вечер, если она писала несколько раз.
     */
    public void appendToday(String section) {
        append(LocalDate.now(), section);
    }

    public void append(LocalDate date, String section) {
        if (section == null || section.isBlank()) {
            return;
        }
        Path file = dir.resolve(DAY.format(date) + ".md");
        String trimmed = section.strip();
        try {
            if (!Files.exists(file)) {
                Files.writeString(file, trimmed + "\n", StandardCharsets.UTF_8);
            } else {
                String existing = Files.readString(file, StandardCharsets.UTF_8).strip();
                Files.writeString(file, existing + "\n\n" + trimmed + "\n", StandardCharsets.UTF_8);
            }
            log.info("Дневник: дописана страница {}", date);
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось записать страницу дневника " + date, e);
        }
    }

    /**
     * Последние страницы, от свежих к старым: для постов в канал и истории
     * обычно берут то, что написано недавно. Ограничение — чтобы не читать
     * весь дневник на каждый запрос.
     */
    public List<Page> recent(int limit) {
        List<Page> pages = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return pages;
        }
        try (Stream<Path> files = Files.list(dir)) {
            files.filter((Path p) -> p.getFileName().toString().endsWith(".md"))
                    .sorted(Comparator.<Path, String>comparing(p -> p.getFileName().toString()).reversed())
                    .limit(limit)
                    .forEach(p -> read(p).ifPresent(pages::add));
        } catch (IOException e) {
            log.warn("Не удалось прочитать дневник: {}", e.getMessage());
        }
        return pages;
    }

    private Optional<Page> read(Path file) {
        String name = file.getFileName().toString();
        if (!name.endsWith(".md")) {
            return Optional.empty();
        }
        String raw = name.substring(0, name.length() - 3);
        try {
            LocalDate date = LocalDate.parse(raw, DAY);
            String text = Files.readString(file, StandardCharsets.UTF_8);
            return Optional.of(new Page(date, text));
        } catch (DateTimeParseException | IOException e) {
            log.debug("Пропускаю файл {}: {}", name, e.getMessage());
            return Optional.empty();
        }
    }

    private static LocalDate parseDate(Path file) {
        String name = file.getFileName().toString();
        return LocalDate.parse(name.substring(0, name.length() - 3), DAY);
    }
}
