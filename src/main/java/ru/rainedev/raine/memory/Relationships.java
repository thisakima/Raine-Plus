package ru.rainedev.raine.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * Отношения с конкретными людьми: близость, охлаждение, заметки о человеке.
 * <p>
 * Живой человек не общается со всеми одинаково. С одним можно материться
 * и слать войсы, с другим — держаться официально, третьего она вообще
 * недолюбливает после ссоры. И она ПОМНИТ мелочи про людей: кличку кота,
 * любимую игру, ту шутку из прошлого месяца.
 * <p>
 * Важно: оценки ставит МОДЕЛЬ, а не код. Код только хранит и подмешивает.
 * Она сама решает через #note_about_person, кто ей близок, кто надоел,
 * и что про кого запомнить. Никаких заготовленных фраз — только её слова.
 */
public final class Relationships {

    private static final Logger log = LoggerFactory.getLogger(Relationships.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Сколько заметок хранить на человека: больше — шум в контексте. */
    private static final int MAX_NOTES = 12;

    private final Path file;
    private final Map<Long, Person> people = new ConcurrentHashMap<>();

    public static final class Person {
        long userId;
        String name = "";
        /** 0..100: насколько близки. Растёт от общения, падает от времени. */
        int intimacy;
        /** До какого времени она холодна (эпоха мс). 0 = не в обиде. */
        long coldUntil;
        /** Заметки, которые ОНА сама решила запомнить. */
        final List<String> notes = new ArrayList<>();
        long lastInteraction;
        /**
         * Пол: "f", "m" или "" если неизвестно. Определяет ОНА САМА по тому,
         * как человек пишет (окончания глаголов, местоимения, упоминания себя),
         * а не по имени или аватарке. Ставит через #note_about_person, обновляет
         * когда замечает новый сигнал. Код сюда ничего не подставляет — иначе
         * «Никита» и «Саша» превращались бы в женщин, а никнейм @dark_lord666
         * считался бы мужским.
         */
        String gender = "";

        // Public getters for Web UI
        public long userId() { return userId; }
        public String name() { return name; }
        public int intimacy() { return intimacy; }
        public long coldUntil() { return coldUntil; }
        public List<String> notes() { return notes; }
        public long lastInteraction() { return lastInteraction; }
        public String gender() { return gender; }
    }

    public Relationships(Path file) {
        this.file = file;
        load();
    }

    /**
     * Отметить взаимодействие: близость растёт понемногу с каждым контактом.
     * Медленно — иначе через неделю все становятся «лучшими друзьями».
     */
    public void touch(long userId, String name) {
        Person p = people.computeIfAbsent(userId, k -> {
            Person np = new Person();
            np.userId = k;
            return np;
        });
        if (name != null && !name.isBlank()) {
            p.name = name;
        }
        p.lastInteraction = System.currentTimeMillis();
        p.intimacy = Math.min(100, p.intimacy + 1);
        // охлаждение само проходит со временем
        persist();
    }

    /**
     * Модель сама решила записать что-то о человеке. Это её решение,
     * её формулировка — код не подсказывает, что запоминать.
     *
     * @param gender "f", "m" или пусто — то, что она сама определила по тому,
     *               как человек пишет. Пустая строка означает «не передавать
     *               старое значение», и поле не затирается.
     */
    public void note(long userId, String name, String text, String feeling, String gender) {
        Person p = people.computeIfAbsent(userId, k -> {
            Person np = new Person();
            np.userId = k;
            return np;
        });
        if (name != null && !name.isBlank()) {
            p.name = name;
        }
        if (text != null && !text.isBlank()) {
            p.notes.add(text.strip());
            while (p.notes.size() > MAX_NOTES) {
                p.notes.remove(0);
            }
        }
        if ("cold".equalsIgnoreCase(feeling)) {
            // обиделась: холодна несколько часов. Сколько именно — решает
            // не код, а она сама позже, когда заметка всплывёт в контексте
            p.coldUntil = System.currentTimeMillis() + 4L * 3600 * 1000;
            p.intimacy = Math.max(0, p.intimacy - 15);
        } else if ("warm".equalsIgnoreCase(feeling)) {
            p.intimacy = Math.min(100, p.intimacy + 8);
            p.coldUntil = 0;
        }
        if (gender != null && !gender.isBlank()) {
            String normalized = normalizeGender(gender);
            if (!normalized.isEmpty()) {
                p.gender = normalized;
            }
        }
        p.lastInteraction = System.currentTimeMillis();
        persist();
        log.info("Заметка о {} ({}): {} ({})", p.name,
                p.gender.isEmpty() ? "?" : p.gender, text, feeling);
    }

    /** Привычный оверлод, чтобы старые вызовы не ломали сборку. */
    public void note(long userId, String name, String text, String feeling) {
        note(userId, name, text, feeling, "");
    }

    /**
     * Пол собеседника, который она сама определила. Возвращает "f", "m" или "",
     * если она его ещё не определила. Используется {@code MessageFormatter} как
     * {@code genderOf} — атрибут {@code gender="female"} на сообщениях появляется
     * только когда ОНА так решила, а не когда имя об этом догадалось.
     */
    public String genderOf(long userId) {
        Person p = people.get(userId);
        return p == null ? "" : p.gender;
    }

    private static String normalizeGender(String value) {
        if (value == null) {
            return "";
        }
        String v = value.strip().toLowerCase();
        if (v.equals("f") || v.equals("female") || v.equals("woman")
                || v.equals("женский") || v.equals("жен") || v.equals("она")) {
            return "f";
        }
        if (v.equals("m") || v.equals("male") || v.equals("man")
                || v.equals("мужской") || v.equals("муж") || v.equals("он")) {
            return "m";
        }
        return "";
    }

    /**
     * Контекст о человеке для промпта. Факты, а не предписания: код
     * сообщает уровень близости и её собственные заметки, а как себя
     * вести — решает модель.
     */
    public String contextFor(long userId) {
        Person p = people.get(userId);
        if (p == null) {
            return "";
        }
        StringBuilder out = new StringBuilder("<about_this_person>\n");
        if (!p.name.isEmpty()) {
            out.append("Name: ").append(p.name).append('\n');
        }
        out.append("Closeness: ").append(describeIntimacy(p.intimacy)).append('\n');
        if (p.coldUntil > System.currentTimeMillis()) {
            long hoursLeft = (p.coldUntil - System.currentTimeMillis()) / 3600_000;
            out.append("You are currently upset with them (about ")
                    .append(Math.max(1, hoursLeft)).append("h more of cooling off). ")
                    .append("You decide how cold to be - you can thaw earlier if they're nice.\n");
        }
        if (!p.notes.isEmpty()) {
            out.append("Things you noted about them (your own words, use them naturally):\n");
            for (String n : p.notes) {
                out.append("  - ").append(n).append('\n');
            }
        }
        if (!p.gender.isEmpty()) {
            out.append("Their gender (as you determined it yourself): ")
                    .append("f".equals(p.gender) ? "female" : "male").append('\n');
        } else {
            out.append("Their gender: you haven't figured it out yet. Use neutral forms until ")
                    .append("you see how they write about themselves ('я пошёл' / 'я пошла'), ")
                    .append("then #note_about_person with gender= to remember it.\n");
        }
        out.append("</about_this_person>\n");
        return out.toString();
    }

    private static String describeIntimacy(int intimacy) {
        if (intimacy >= 80) return "very close - you can be blunt, swear, send voice notes, share personal things";
        if (intimacy >= 55) return "friends - relaxed, jokes allowed, some personal topics";
        if (intimacy >= 30) return "acquaintances - friendly but reserved";
        if (intimacy >= 10) return "barely know them - polite, short, guarded";
        return "stranger - distant and cautious";
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            JsonNode root = MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
            root.fields().forEachRemaining(entry -> {
                try {
                    long id = Long.parseLong(entry.getKey());
                    JsonNode v = entry.getValue();
                    Person p = new Person();
                    p.userId = id;
                    p.name = v.path("name").asText("");
                    p.intimacy = v.path("intimacy").asInt(0);
                    p.coldUntil = v.path("coldUntil").asLong(0);
                    p.lastInteraction = v.path("lastInteraction").asLong(0);
                    p.gender = v.path("gender").asText("");
                    for (JsonNode n : v.path("notes")) {
                        p.notes.add(n.asText());
                    }
                    people.put(id, p);
                } catch (RuntimeException ignored) { }
            });
            log.info("Загружено отношений: {}", people.size());
        } catch (IOException e) {
            log.warn("Не удалось прочитать {}: {}", file, e.getMessage());
        }
    }

    private synchronized void persist() {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            ObjectNode root = MAPPER.createObjectNode();
            for (Person p : people.values()) {
                ObjectNode v = root.putObject(String.valueOf(p.userId));
                v.put("name", p.name);
                v.put("intimacy", p.intimacy);
                v.put("coldUntil", p.coldUntil);
                v.put("lastInteraction", p.lastInteraction);
                v.put("gender", p.gender);
                var arr = v.putArray("notes");
                p.notes.forEach(arr::add);
            }
            Files.writeString(file, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Не удалось сохранить отношения: {}", e.getMessage());
        }
    }
}
