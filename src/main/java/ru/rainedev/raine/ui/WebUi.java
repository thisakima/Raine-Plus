package ru.rainedev.raine.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import it.tdlight.jni.TdApi;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.core.Mood;
import ru.rainedev.raine.core.NotificationLoop;
import ru.rainedev.raine.core.PhraseCollector;
import ru.rainedev.raine.core.Surroundings;
import ru.rainedev.raine.llm.LlmClient;
import ru.rainedev.raine.llm.Transcript;
import ru.rainedev.raine.memory.Diary;
import ru.rainedev.raine.memory.DiaryEntry;
import ru.rainedev.raine.memory.DiaryMemory;
import ru.rainedev.raine.memory.Relationships;
import ru.rainedev.raine.memory.WorkingMemory;
import ru.rainedev.raine.telegram.Telegram;
import ru.rainedev.raine.telegram.TelegramDelivery;

/**
 * Локальная веб-панель: аниме-эстетика дашборд для Юкико.
 * <p>
 * Четыре вкладки:
 * <ul>
 *   <li>{@code /} — Главная: карточка персонажа, настроение, фото</li>
 *   <li>{@code /dashboard} — Пульт: эквалайзер настроения, таймлайн дня</li>
 *   <li>{@code /thoughts} — Мысли и увлечения</li>
 *   <li>{@code /stats} — Статистика: stacked bar charts за 10 дней</li>
 * </ul>
 * API эндпоинты остаются для совместимости.
 */
public final class WebUi implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WebUi.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final int DEFAULT_TAIL_LINES = 300;
    private static final int MAX_TAIL_LINES = 2000;

    private final HttpServer server;
    private final Path logFile;
    private final Path galleryPath;
    private final long startedAt;

    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();

    private TelegramDelivery delivery;
    private BooleanSupplier processAlive = () -> true;
    private Supplier<String> customStatus = () -> "";
    private LongSupplier generation = () -> 0;
    private NotificationLoop loop;
    private Relationships relationships;
    private Diary diary;
    private WorkingMemory workingMemory;
    private Mood mood;
    private Surroundings surroundings;
    private PhraseCollector phraseCollector;
    private Telegram telegram;
    private LlmClient llmClient;

    /** Global stats tracker instance for recording replies and tokens. */
    public static final DailyStatsTracker STATS = new DailyStatsTracker();

    /** Tracks daily replies and tokens in memory. */
    public static final class DailyStatsTracker {
        private final Map<LocalDate, DayStats> days = new ConcurrentHashMap<>();

        public void recordReply(long chatId, boolean isGroup, boolean isOwner) {
            DayStats stats = days.computeIfAbsent(LocalDate.now(), k -> new DayStats());
            if (isOwner) stats.repliesToOwner++;
            else if (isGroup) stats.repliesInGroups++;
            else stats.repliesToOthers++;
        }

        public void recordTokens(String model, int tokens) {
            DayStats stats = days.computeIfAbsent(LocalDate.now(), k -> new DayStats());
            switch (model.toLowerCase()) {
                case "ds", "deepseek" -> stats.tokensDs += tokens;
                case "local" -> stats.tokensLocal += tokens;
                case "free" -> stats.tokensFree += tokens;
                case "paid" -> stats.tokensPaid += tokens;
                case "ds2", "deepseek2" -> stats.tokensDs2 += tokens;
                case "grp", "group" -> stats.tokensGrp += tokens;
                default -> stats.tokensDs += tokens;
            }
        }

        public DayStats getDay(LocalDate date) {
            return days.getOrDefault(date, new DayStats());
        }

        public static final class DayStats {
            int repliesToOwner = 0;
            int repliesInGroups = 0;
            int repliesToOthers = 0;
            long tokensDs = 0;
            long tokensLocal = 0;
            long tokensFree = 0;
            long tokensPaid = 0;
            long tokensDs2 = 0;
            long tokensGrp = 0;
        }
    }

    private WebUi(HttpServer server, Path logFile, Path galleryPath) {
        this.server = server;
        this.logFile = logFile;
        this.galleryPath = galleryPath;
        this.startedAt = System.currentTimeMillis();
    }

    public static WebUi start(int port, Path logFile, Path galleryPath) throws IOException {
        if (port <= 0) {
            return null;
        }
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(Executors.newFixedThreadPool(4,
                r -> {
                    Thread t = new Thread(r, "raine-webui");
                    t.setDaemon(true);
                    return t;
                }));
        WebUi ui = new WebUi(server, logFile, galleryPath);
        server.createContext("/", ui::index);
        server.createContext("/api/tail", ui::tail);
        server.createContext("/api/status", ui::status);
        server.createContext("/api/logfile", ui::logfile);
        server.createContext("/api/relationships", ui::relationships);
        server.createContext("/api/diary", ui::diary);
        server.createContext("/api/working-memory", ui::workingMemory);
        server.createContext("/api/queue", ui::queue);
        server.createContext("/api/mood", ui::mood);
        server.createContext("/api/thoughts", ui::thoughts);
        server.createContext("/api/interests", ui::interests);
        server.createContext("/api/schedule", ui::schedule);
        server.createContext("/api/stats", ui::stats);
        server.createContext("/api/user-photo/", ui::userPhoto);
        server.createContext("/api/my-photo", ui::myPhoto);

        // Static file handler for gallery images
        server.createContext("/gallery/", exchange -> {
            String path = exchange.getRequestURI().getPath().substring("/gallery/".length());
            if (path.isEmpty() || path.contains("..")) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            Path file = galleryPath.resolve(path).normalize();
            if (!file.startsWith(galleryPath.toAbsolutePath().normalize()) || !Files.exists(file)) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            String mime = Files.probeContentType(file);
            if (mime == null) mime = "application/octet-stream";
            byte[] bytes = Files.readAllBytes(file);
            exchange.getResponseHeaders().set("Content-Type", mime);
            exchange.getResponseHeaders().set("Cache-Control", "public, max-age=31536000");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });

        server.start();
        log.info("Веб-панель слушает http://127.0.0.1:{} (только локально)", port);
        return ui;
    }

    public WebUi delivery(TelegramDelivery delivery) {
        this.delivery = delivery;
        return this;
    }

    public WebUi generation(LongSupplier generation) {
        this.generation = generation;
        return this;
    }

    public WebUi alive(BooleanSupplier alive) {
        this.processAlive = alive;
        return this;
    }

    public WebUi customStatus(Supplier<String> status) {
        this.customStatus = status;
        return this;
    }

    public WebUi loop(NotificationLoop loop) {
        this.loop = loop;
        return this;
    }

    public WebUi relationships(Relationships relationships) {
        this.relationships = relationships;
        return this;
    }

    public WebUi diary(Diary diary) {
        this.diary = diary;
        return this;
    }

    public WebUi diaryMemory(DiaryMemory diaryMemory) {
        // Not used directly but kept for API compatibility
        return this;
    }

    public WebUi workingMemory(WorkingMemory workingMemory) {
        this.workingMemory = workingMemory;
        return this;
    }

    public WebUi phraseCollector(PhraseCollector phraseCollector) {
        this.phraseCollector = phraseCollector;
        return this;
    }

    public WebUi mood(Mood mood) {
        this.mood = mood;
        return this;
    }

    public WebUi surroundings(Surroundings surroundings) {
        this.surroundings = surroundings;
        return this;
    }

    public WebUi telegram(Telegram telegram) {
        this.telegram = telegram;
        return this;
    }

    public WebUi llmClient(LlmClient llmClient) {
        this.llmClient = llmClient;
        return this;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        try {
            server.stop(0);
        } catch (RuntimeException e) {
            log.debug("Веб-панель закрылась неаккуратно: {}", e.getMessage());
        }
    }

    // ───────────────────── endpoints ─────────────────────

    private void index(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(405, -1);
            return;
        }
        byte[] body = INDEX_HTML.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    private void tail(HttpExchange ex) throws IOException {
        requests.incrementAndGet();
        try {
            int n = intParam(ex, "n", DEFAULT_TAIL_LINES);
            if (n < 1) n = 1;
            if (n > MAX_TAIL_LINES) n = MAX_TAIL_LINES;
            long since = longParam(ex, "since", 0);
            TailResult tail = readTail(n, since);
            String json = "{\"lines\":" + escapeJsonArray(tail.lines)
                    + ",\"size\":" + tail.size
                    + ",\"since\":" + tail.cursor + "}";
            sendJson(ex, json);
        } catch (RuntimeException e) {
            errors.incrementAndGet();
            sendJson(ex, "{\"error\":" + quote(e.getMessage()) + "}");
        }
    }

    private void status(HttpExchange ex) throws IOException {
        requests.incrementAndGet();
        boolean telegramOnline = delivery != null && delivery.online();
        long gen = generation != null ? generation.getAsLong() : 0;
        long uptime = System.currentTimeMillis() - startedAt;
        int queued = loop != null ? loop.queued() : 0;
        String json = "{"
                + "\"telegramOnline\":" + telegramOnline + ","
                + "\"processAlive\":" + safe(processAlive) + ","
                + "\"generation\":" + gen + ","
                + "\"uptimeMs\":" + uptime + ","
                + "\"startedAt\":" + quote(formatInstant(Instant.ofEpochMilli(startedAt))) + ","
                + "\"requests\":" + requests.get() + ","
                + "\"errors\":" + errors.get() + ","
                + "\"queued\":" + queued + ","
                + "\"custom\":" + quote(safeString(customStatus))
                + "}";
        sendJson(ex, json);
    }

    private void logfile(HttpExchange ex) throws IOException {
        sendJson(ex, "{\"path\":" + quote(logFile.toAbsolutePath().toString()) + "}");
    }

    private void relationships(HttpExchange ex) throws IOException {
        requests.incrementAndGet();
        try {
            String path = ex.getRequestURI().getPath();
            if ("/api/relationships".equals(path)) {
                if (relationships == null) {
                    sendJson(ex, "[]");
                    return;
                }
                List<Map<String, Object>> list = new ArrayList<>();
                var field = Relationships.class.getDeclaredField("people");
                field.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<Long, Relationships.Person> people = (Map<Long, Relationships.Person>) field.get(relationships);
                for (Relationships.Person p : people.values()) {
                    list.add(personToMap(p));
                }
                list.sort(Comparator.comparing(m -> -((Integer) m.get("intimacy"))));
                sendJson(ex, MAPPER.writeValueAsString(list));
            } else {
                String idStr = path.substring("/api/relationships/".length());
                long userId = Long.parseLong(idStr);
                if (relationships == null) {
                    sendJson(ex, "{\"error\":\"relationships not available\"}");
                    return;
                }
                var field = Relationships.class.getDeclaredField("people");
                field.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<Long, Relationships.Person> people = (Map<Long, Relationships.Person>) field.get(relationships);
                Relationships.Person p = people.get(userId);
                if (p == null) {
                    sendJson(ex, "{\"error\":\"not found\"}");
                    return;
                }
                sendJson(ex, MAPPER.writeValueAsString(personToMap(p)));
            }
        } catch (RuntimeException | ReflectiveOperationException e) {
            errors.incrementAndGet();
            sendJson(ex, "{\"error\":" + quote(e.getMessage()) + "}");
        }
    }

    private Map<String, Object> personToMap(Relationships.Person p) {
        var map = new ConcurrentHashMap<String, Object>();
        map.put("userId", p.userId());
        map.put("name", p.name());
        map.put("intimacy", p.intimacy());
        map.put("intimacyLabel", describeIntimacy(p.intimacy()));
        map.put("coldUntil", p.coldUntil());
        map.put("coldUntilHours", p.coldUntil() > System.currentTimeMillis()
                ? (p.coldUntil() - System.currentTimeMillis()) / 3600_000 + 1
                : 0);
        map.put("gender", p.gender());
        map.put("genderLabel", p.gender().isEmpty() ? "unknown" : ("f".equals(p.gender()) ? "female" : "male"));
        map.put("notes", p.notes());
        map.put("lastInteraction", p.lastInteraction());
        map.put("lastInteractionAgo", p.lastInteraction() > 0
                ? (System.currentTimeMillis() - p.lastInteraction()) / 60_000
                : null);
        return map;
    }

    private void diary(HttpExchange ex) throws IOException {
        requests.incrementAndGet();
        try {
            int limit = intParam(ex, "limit", 50);
            if (limit < 1) limit = 1;
            if (limit > 500) limit = 500;
            if (diary == null) {
                sendJson(ex, "[]");
                return;
            }
            List<Map<String, Object>> list = new ArrayList<>();
            for (DiaryEntry entry : diary.query(new double[0]).stream()
                    .limit(limit)
                    .map(Diary.Match::entry)
                    .toList()) {
                var map = new ConcurrentHashMap<String, Object>();
                map.put("id", entry.id());
                map.put("body", entry.body());
                map.put("score", entry.metadata().score());
                map.put("confidence", entry.metadata().confidence());
                map.put("lastUsed", entry.metadata().lastUsed());
                map.put("usageCount", entry.metadata().usageCount());
                list.add(map);
            }
            sendJson(ex, MAPPER.writeValueAsString(list));
        } catch (RuntimeException e) {
            errors.incrementAndGet();
            sendJson(ex, "{\"error\":" + quote(e.getMessage()) + "}");
        }
    }

    private void workingMemory(HttpExchange ex) throws IOException {
        requests.incrementAndGet();
        try {
            if (workingMemory == null) {
                sendJson(ex, "{\"content\":\"\"}");
                return;
            }
            String content = workingMemory.read();
            sendJson(ex, "{\"content\":" + quote(content) + "}");
        } catch (RuntimeException e) {
            errors.incrementAndGet();
            sendJson(ex, "{\"error\":" + quote(e.getMessage()) + "}");
        }
    }

    private void queue(HttpExchange ex) throws IOException {
        requests.incrementAndGet();
        try {
            if (loop == null) {
                sendJson(ex, "{\"queued\":0,\"items\":[]}");
                return;
            }
            var map = new ConcurrentHashMap<String, Object>();
            map.put("queued", loop.queued());
            var field = NotificationLoop.class.getDeclaredField("queue");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.concurrent.LinkedBlockingDeque<ru.rainedev.raine.core.Notification> queue =
                    (java.util.concurrent.LinkedBlockingDeque<ru.rainedev.raine.core.Notification>) field.get(loop);
            List<Map<String, Object>> items = new ArrayList<>();
            for (ru.rainedev.raine.core.Notification n : queue) {
                var item = new ConcurrentHashMap<String, Object>();
                item.put("impulse", n.impulse());
                item.put("ownText", n.ownText());
                item.put("tools", n.tools().names());
                String text = n.text();
                if (text.length() > 200) text = text.substring(0, 200) + "…";
                item.put("text", text);
                items.add(item);
            }
            map.put("items", items);
            sendJson(ex, MAPPER.writeValueAsString(map));
        } catch (RuntimeException | ReflectiveOperationException e) {
            errors.incrementAndGet();
            sendJson(ex, "{\"error\":" + quote(e.getMessage()) + "}");
        }
    }

    private void mood(HttpExchange ex) throws IOException {
        requests.incrementAndGet();
        try {
            var map = new ConcurrentHashMap<String, Object>();

            int energy = mood != null ? (int) Math.round(mood.energy() * 100) : 50;

            int affection = 50;
            int trust = 50;
            if (relationships != null) {
                try {
                    var field = Relationships.class.getDeclaredField("people");
                    field.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    Map<Long, Relationships.Person> people = (Map<Long, Relationships.Person>) field.get(relationships);
                    Relationships.Person owner = people.values().stream()
                            .max(Comparator.comparingInt(Relationships.Person::intimacy))
                            .orElse(null);
                    if (owner != null) {
                        affection = Math.min(100, owner.intimacy() + 20);
                        trust = Math.min(100, owner.intimacy() + 10);
                    }
                } catch (ReflectiveOperationException ignored) {}
            }

            int communication = loop != null ? Math.min(100, loop.queued() * 10 + 30) : 50;
            int curiosity = diary != null && !diary.isEmpty() ? Math.min(100, diary.size() / 2 + 40) : 50;
            int tension = Math.max(0, 100 - energy - (mood != null ? (int)(Math.abs(mood.energy() - 0.5) * 100) : 0));

            int irritation = 10;
            if (relationships != null) {
                try {
                    var field = Relationships.class.getDeclaredField("people");
                    field.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    Map<Long, Relationships.Person> people = (Map<Long, Relationships.Person>) field.get(relationships);
                    long coldCount = people.values().stream()
                            .filter(p -> p.coldUntil() > System.currentTimeMillis())
                            .count();
                    irritation = Math.min(100, (int)(coldCount * 15 + 10));
                } catch (ReflectiveOperationException ignored) {}
            }

            // Физическое состояние
            String physicalState = "нормальное";
            if (mood != null) {
                double e = mood.energy();
                if (e < 0.2) physicalState = "очень низкая энергия";
                else if (e < 0.35) physicalState = "усталость";
                else if (e < 0.5) physicalState = "спокойное";
                else if (e < 0.7) physicalState = "нормальное";
                else physicalState = "высокая энергия";
            }

            // Эмоциональное состояние
            String emotionalState = "спокойное";
            if (mood != null) {
                double e = mood.energy();
                if (mood != null && irritation > 60) emotionalState = "раздраженное";
                else if (mood != null && irritation > 40) emotionalState = "напряжённое";
                else if (mood != null && irritation > 20) emotionalState = "лёгкое беспокойство";
                else emotionalState = "спокойное";
            }

            String moodTag;
            if (energy < 20) moodTag = "сонное";
            else if (energy < 35) moodTag = "уставшее";
            else if (energy < 50) moodTag = "спокойное";
            else if (energy < 70) moodTag = "игривое";
            else moodTag = "нежное";

            String activity = "Дома: гитара, игры, аниме, ночные разговоры";
            String location = "Дома";
            if (surroundings != null) {
                String around = surroundings.asPromptSuffix();
                if (around.contains("колледж") || around.contains("учеб")) { activity = "На паре в колледже"; location = "Колледж"; }
                else if (around.contains("дорог") || around.contains("транспорт")) { activity = "В дороге"; location = "Транспорт"; }
                else if (around.contains("вечер") || around.contains("дома")) { activity = "Дома: гитара, игры, аниме, ночные разговоры"; location = "Дома"; }
                else if (around.contains("утро")) { activity = "Просыпаюсь, кофе, готовлюсь"; location = "Дома"; }
                else if (around.contains("сон") || around.contains("спит")) { activity = "Спит"; location = "Спальня"; }
            }

            String lastMessageAgo = "—";
            if (loop != null && !loop.context().isEmpty()) {
                lastMessageAgo = "только что";
            }

            String yourMood = "ласковый";
            if (relationships != null) {
                try {
                    var field = Relationships.class.getDeclaredField("people");
                    field.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    Map<Long, Relationships.Person> people = (Map<Long, Relationships.Person>) field.get(relationships);
                    Relationships.Person owner = people.values().stream()
                            .max(Comparator.comparingInt(Relationships.Person::intimacy))
                            .orElse(null);
                    if (owner != null) {
                        if (owner.coldUntil() > System.currentTimeMillis()) yourMood = "холодный";
                        else if (owner.intimacy() > 80) yourMood = "тёплый";
                        else if (owner.intimacy() > 50) yourMood = "ласковый";
                        else yourMood = "нейтральный";
                    }
                } catch (ReflectiveOperationException ignored) {}
            }

            String model = "ds";
            if (surroundings != null) {
                String around = surroundings.asPromptSuffix();
                if (around.contains("колледж")) model = "ds (колледж)";
            }

            map.put("energy", energy);
            map.put("communication", communication);
            map.put("curiosity", curiosity);
            map.put("tension", tension);
            map.put("affection", affection);
            map.put("trust", trust);
            map.put("irritation", irritation);
            map.put("physicalState", physicalState);
            map.put("emotionalState", emotionalState);
            map.put("moodTag", moodTag);
            map.put("activity", activity);
            map.put("location", location);
            map.put("lastMessageAgo", lastMessageAgo);
            map.put("yourMood", yourMood);
            map.put("model", llmClient != null ? llmClient.modelName() : "ds");

            sendJson(ex, MAPPER.writeValueAsString(map));
        } catch (RuntimeException e) {
            errors.incrementAndGet();
            sendJson(ex, "{\"error\":" + quote(e.getMessage()) + "}");
        }
    }

    private void thoughts(HttpExchange ex) throws IOException {
        requests.incrementAndGet();
        try {
            List<Map<String, Object>> list = new ArrayList<>();

            if (diary != null) {
                for (DiaryEntry entry : diary.query(new double[0]).stream()
                        .limit(10)
                        .map(Diary.Match::entry)
                        .toList()) {
                    String body = entry.body();
                    String emotion = extractEmotion(body);
                    String time = formatTimeAgo(entry.id());
                    if (time == null) time = "недавно";

                    var map = new ConcurrentHashMap<String, Object>();
                    map.put("source", "diary");
                    map.put("time", time);
                    map.put("emotion", emotion);
                    map.put("text", truncate(body, 200));
                    list.add(map);
                }
            }

            if (workingMemory != null && list.size() < 15) {
                String wm = workingMemory.read();
                if (!wm.isBlank()) {
                    for (String line : wm.split("\n")) {
                        line = line.strip();
                        if (line.isEmpty() || line.startsWith("-") || line.startsWith("•")) continue;
                        if (line.length() > 20) {
                            var map = new ConcurrentHashMap<String, Object>();
                            map.put("source", "memory");
                            map.put("time", "сегодня");
                            map.put("emotion", "внимание");
                            map.put("text", truncate(line, 200));
                            list.add(map);
                            if (list.size() >= 15) break;
                        }
                    }
                }
            }

            if (phraseCollector != null && list.size() < 15) {
                String sample = phraseCollector.sample();
                if (!sample.isBlank()) {
                    for (String line : sample.split("\n")) {
                        line = line.strip();
                        if (line.startsWith("- ")) line = line.substring(2);
                        if (line.length() > 10) {
                            var map = new ConcurrentHashMap<String, Object>();
                            map.put("source", "chat");
                            map.put("time", "в чате");
                            map.put("emotion", "живое");
                            map.put("text", truncate(line, 200));
                            list.add(map);
                            if (list.size() >= 15) break;
                        }
                    }
                }
            }

            sendJson(ex, MAPPER.writeValueAsString(list));
        } catch (RuntimeException e) {
            errors.incrementAndGet();
            sendJson(ex, "{\"error\":" + quote(e.getMessage()) + "}");
        }
    }

    private void interests(HttpExchange ex) throws IOException {
        requests.incrementAndGet();
        try {
            Map<String, InterestData> interestMap = new HashMap<>();

            if (diary != null) {
                for (DiaryEntry entry : diary.query(new double[0]).stream()
                        .limit(200)
                        .map(Diary.Match::entry)
                        .toList()) {
                    extractTopics(entry.body(), interestMap, entry.metadata().confidence());
                }
            }

            if (phraseCollector != null) {
                String sample = phraseCollector.sample();
                for (String line : sample.split("\n")) {
                    line = line.strip();
                    if (line.startsWith("- ")) line = line.substring(2);
                    extractTopics(line, interestMap, 0.5);
                }
            }

            List<Map<String, Object>> list = interestMap.values().stream()
                    .sorted(Comparator.comparingDouble((InterestData d) -> d.weight).reversed())
                    .limit(12)
                    .map(d -> {
                        var map = new ConcurrentHashMap<String, Object>();
                        map.put("name", d.name);
                        map.put("value", Math.round(d.weight * 100.0) / 100.0);
                        map.put("color", d.color);
                        return map;
                    })
                    .collect(Collectors.toList());

            sendJson(ex, MAPPER.writeValueAsString(list));
        } catch (RuntimeException e) {
            errors.incrementAndGet();
            sendJson(ex, "{\"error\":" + quote(e.getMessage()) + "}");
        }
    }

    private void schedule(HttpExchange ex) throws IOException {
        requests.incrementAndGet();
        try {
            var map = new ConcurrentHashMap<String, Object>();

            LocalTime now = LocalTime.now();
            List<Map<String, Object>> blocks = buildScheduleBlocks(now);
            map.put("schedule", blocks);

            List<Integer> moodLine = new ArrayList<>();
            for (int h = 0; h < 24; h++) {
                int val = computeMoodForHour(h, now);
                moodLine.add(val);
            }
            map.put("moodLine", moodLine);

            List<Integer> thoughtEvents = new ArrayList<>();
            for (int h = 0; h < 24; h++) {
                int count = countDiaryEntriesForHour(h);
                thoughtEvents.add(count);
            }
            map.put("thoughtEvents", thoughtEvents);

            // Дополнительный контекст
            map.put("currentHour", now.getHour());
            map.put("currentMinute", now.getMinute());
            
            if (surroundings != null) {
                String around = surroundings.asPromptSuffix();
                map.put("surroundings", around);
                
                // Текущая локация/активность
                String activity = "Неизвестно";
                String location = "Неизвестно";
                if (around.contains("колледж") || around.contains("учеб")) {
                    activity = "Учеба"; location = "Колледж";
                } else if (around.contains("дорог") || around.contains("транспорт")) {
                    activity = "В пути"; location = "Транспорт";
                } else if (around.contains("вечер") || around.contains("дома")) {
                    activity = "Отдых"; location = "Дома";
                } else if (around.contains("утро")) {
                    activity = "Подъём"; location = "Дома";
                } else if (around.contains("сон") || around.contains("спит")) {
                    activity = "Сон"; location = "Спальня";
                }
                map.put("currentActivity", activity);
                map.put("currentLocation", location);
            }

            sendJson(ex, MAPPER.writeValueAsString(map));
        } catch (RuntimeException e) {
            errors.incrementAndGet();
            sendJson(ex, "{\"error\":" + quote(e.getMessage()) + "}");
        }
    }

    private void stats(HttpExchange ex) throws IOException {
        requests.incrementAndGet();
        try {
            var map = new ConcurrentHashMap<String, Object>();

            List<String> dates = new ArrayList<>();
            List<List<Integer>> replies = new ArrayList<>();
            List<List<Integer>> tokens = new ArrayList<>();

            LocalDate today = LocalDate.now();
            for (int i = 9; i >= 0; i--) {
                LocalDate d = today.minusDays(i);
                dates.add(d.format(DateTimeFormatter.ofPattern("dd.MM")));

                DailyStatsTracker.DayStats dayStats = STATS.getDay(d);
                replies.add(List.of(dayStats.repliesToOwner, dayStats.repliesInGroups, dayStats.repliesToOthers));

                tokens.add(List.of(
                        (int)(dayStats.tokensDs / 1000),
                        (int)(dayStats.tokensLocal / 1000),
                        (int)(dayStats.tokensFree / 1000),
                        (int)(dayStats.tokensPaid / 1000),
                        (int)(dayStats.tokensDs2 / 1000),
                        (int)(dayStats.tokensGrp / 1000)
                ));
            }
            map.put("dates", dates);
            map.put("replies", replies);
            map.put("tokens", tokens);

            sendJson(ex, MAPPER.writeValueAsString(map));
        } catch (RuntimeException e) {
            errors.incrementAndGet();
            sendJson(ex, "{\"error\":" + quote(e.getMessage()) + "}");
        }
    }

    private void userPhoto(HttpExchange ex) throws IOException {
        requests.incrementAndGet();
        if (telegram == null) {
            ex.sendResponseHeaders(503, -1);
            return;
        }
        String path = ex.getRequestURI().getPath().substring("/api/user-photo/".length());
        if (path.isEmpty()) {
            ex.sendResponseHeaders(400, -1);
            return;
        }
        long userId;
        try {
            userId = Long.parseLong(path);
        } catch (NumberFormatException e) {
            ex.sendResponseHeaders(400, -1);
            return;
        }
        try {
            Optional<TdApi.ChatPhoto> photo = telegram.userPhoto(userId);
            if (photo.isEmpty() || photo.get().sizes == null || photo.get().sizes.length == 0) {
                ex.sendResponseHeaders(404, -1);
                return;
            }
            TdApi.PhotoSize largest = photo.get().sizes[photo.get().sizes.length - 1];
            Path file = telegram.download(largest.photo.id).orElse(null);
            if (file == null || !Files.exists(file)) {
                ex.sendResponseHeaders(404, -1);
                return;
            }
            byte[] bytes = Files.readAllBytes(file);
            ex.getResponseHeaders().set("Content-Type", "image/jpeg");
            ex.getResponseHeaders().set("Cache-Control", "public, max-age=3600");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        } catch (RuntimeException e) {
            log.debug("Не удалось получить фото пользователя {}: {}", userId, e.getMessage());
            ex.sendResponseHeaders(500, -1);
        }
    }

    private void myPhoto(HttpExchange ex) throws IOException {
        requests.incrementAndGet();
        if (telegram == null) {
            ex.sendResponseHeaders(503, -1);
            return;
        }
        try {
            long myId = telegram.myId();
            Optional<TdApi.ChatPhoto> photo = telegram.userPhoto(myId);
            if (photo.isEmpty() || photo.get().sizes == null || photo.get().sizes.length == 0) {
                ex.sendResponseHeaders(404, -1);
                return;
            }
            TdApi.PhotoSize largest = photo.get().sizes[photo.get().sizes.length - 1];
            Path file = telegram.download(largest.photo.id).orElse(null);
            if (file == null || !Files.exists(file)) {
                ex.sendResponseHeaders(404, -1);
                return;
            }
            byte[] bytes = Files.readAllBytes(file);
            ex.getResponseHeaders().set("Content-Type", "image/jpeg");
            ex.getResponseHeaders().set("Cache-Control", "public, max-age=3600");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        } catch (RuntimeException e) {
            log.debug("Не удалось получить своё фото: {}", e.getMessage());
            ex.sendResponseHeaders(500, -1);
        }
    }

    // ───────────────────── helpers ─────────────────────

    private record TailResult(java.util.List<String> lines, long size, long cursor) {}

    private TailResult readTail(int n, long since) {
        if (logFile == null || !Files.exists(logFile)) {
            return new TailResult(java.util.List.of("(лог ещё не создан)"), 0, 0);
        }
        long size;
        try {
            size = Files.size(logFile);
        } catch (IOException e) {
            return new TailResult(java.util.List.of("(не удалось прочитать лог: " + e.getMessage() + ")"), 0, 0);
        }
        if (since >= size) {
            return new TailResult(java.util.List.of(), size, size);
        }
        long guess = Math.min(size, Math.max(8192, (long) n * 480));
        long start = Math.max(since, size - guess);
        byte[] bytes;
        try (var in = Files.newInputStream(logFile)) {
            in.skip(start);
            bytes = in.readNBytes((int) (size - start));
        } catch (IOException e) {
            return new TailResult(java.util.List.of("(ошибка чтения: " + e.getMessage() + ")"), size, size);
        }
        String chunk = new String(bytes, StandardCharsets.UTF_8);
        if (start > since && start > 0) {
            int firstNl = chunk.indexOf('\n');
            if (firstNl >= 0) {
                chunk = chunk.substring(firstNl + 1);
            }
        }
        String[] raw = chunk.split("\n", -1);
        int last = raw.length;
        while (last > 0 && raw[last - 1].isEmpty()) {
            last--;
        }
        int take = Math.min(n, last);
        java.util.List<String> lines = new java.util.ArrayList<>(take);
        for (int i = last - take; i < last; i++) {
            lines.add(raw[i]);
        }
        return new TailResult(lines, size, size);
    }

    private static int intParam(HttpExchange ex, String name, int def) {
        String v = param(ex, name);
        if (v.isEmpty()) return def;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static long longParam(HttpExchange ex, String name, long def) {
        String v = param(ex, name);
        if (v.isEmpty()) return def;
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String param(HttpExchange ex, String name) {
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) return "";
        for (String part : q.split("&")) {
            int eq = part.indexOf('=');
            if (eq < 0) continue;
            if (part.substring(0, eq).equals(name)) {
                return java.net.URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private static void sendJson(HttpExchange ex, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    private static String escapeJsonArray(java.util.List<String> lines) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) out.append(',');
            out.append(quote(lines.get(i)));
        }
        return out.append(']').toString();
    }

    private static String quote(String s) {
        if (s == null) return "null";
        StringBuilder out = new StringBuilder(s.length() + 2);
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    private static boolean safe(BooleanSupplier s) {
        try {
            return s.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String safeString(Supplier<String> s) {
        try {
            String v = s.get();
            return v == null ? "" : v;
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String formatInstant(Instant instant) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault())
                .format(instant);
    }

    private static String describeIntimacy(int intimacy) {
        if (intimacy >= 80) return "very close";
        if (intimacy >= 55) return "friends";
        if (intimacy >= 30) return "acquaintances";
        if (intimacy >= 10) return "barely know";
        return "stranger";
    }

    private String extractEmotion(String text) {
        if (text == null) return "нейтральное";
        String low = text.toLowerCase();
        if (low.contains("радост") || low.contains("счастлив") || low.contains("круто") || low.contains("обожаю") || low.contains("люблю") || low.contains("ахах") || low.contains("хаха") || low.contains("лол") || low.contains("ржу")) return "радость";
        if (low.contains("грустн") || low.contains("печаль") || low.contains("тоск") || low.contains("одинок") || low.contains("скучаю")) return "грусть";
        if (low.contains("зл") || low.contains("бесит") || low.contains("раздражает") || low.contains("ебать") || low.contains("бля") || low.contains("пиздец") || low.contains("ненавиж")) return "злость";
        if (low.contains("тревог") || low.contains("волную") || low.contains("страш") || low.contains("паник")) return "тревога";
        if (low.contains("уютн") || low.contains("спокойн") || low.contains("расслаб") || low.contains("тихо") || low.contains("мирн")) return "уютно";
        if (low.contains("любопытн") || low.contains("интересн") || low.contains("хочу узнать") || low.contains("задался вопрос")) return "любопытство";
        if (low.contains("вдохнов") || low.contains("мотив") || low.contains("энерги") || low.contains("хочу сделать")) return "вдохновение";
        if (low.contains("устал") || low.contains("устала") || low.contains("экзамен") || low.contains("дедлайн") || low.contains("много дел")) return "усталость";
        return "нейтральное";
    }

    private String formatTimeAgo(String entryId) {
        try {
            long timestamp = Long.parseLong(entryId);
            long diff = System.currentTimeMillis() / 1000 - timestamp;
            if (diff < 60) return "только что";
            if (diff < 3600) return (diff / 60) + " мин назад";
            if (diff < 86400) return (diff / 3600) + " ч назад";
            return (diff / 86400) + " дн назад";
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private record InterestData(String name, double weight, String color) {}

    private static final String[] TOPIC_KEYWORDS = {
        "hades", "гейм", "игра", "steam", "рпг",
        "чай", "пуэр", "улун", "заварк", "чайная",
        "гитар", "музык", "аккорд", "песен", "рифф",
        "аниме", "манга", "магическ", "девчонк", "сериал",
        "кот", "кошк", "киса", "пушок", "мур",
        "прогулк", "ночн", "улца", "парк", "свеж",
        "колледж", "учеб", "пара", "лекци", "зачёт", "экзамен",
        "код", "программ", "java", "python", "бот", "телеграм",
        "рисов", "арт", "нарисов", "скетч",
        "фото", "селфи", "камер", "объектив",
        "сон", "спать", "просыпа", "утро", "подъём",
        "еда", "кушать", "обед", "ужин", "завтрак",
        "друг", "подруг", "общ", "встреч", "звонок",
        "мысл", "дума", "размышл", "идея", "мысль"
    };

    private static final String[] TOPIC_COLORS = {
        "#ff6b9d", "#4ecdc4", "#ffd93d", "#ff8c42", "#a855f7",
        "#06b6d4", "#f87171", "#4ade80", "#60a5fa", "#fbbf24"
    };

    private void extractTopics(String text, Map<String, InterestData> map, double confidence) {
        if (text == null || text.isBlank()) return;
        String low = text.toLowerCase();
        for (int i = 0; i < TOPIC_KEYWORDS.length; i++) {
            String kw = TOPIC_KEYWORDS[i];
            if (low.contains(kw)) {
                String label = formatTopicLabel(kw);
                map.merge(label,
                        new InterestData(label, confidence * 0.5, TOPIC_COLORS[i % TOPIC_COLORS.length]),
                        (old, nw) -> new InterestData(old.name, old.weight + nw.weight, old.color));
            }
        }
    }

    private String formatTopicLabel(String kw) {
        return switch (kw) {
            case "hades", "гейм", "игра", "steam", "рпг" -> "Hades / игры";
            case "чай", "пуэр", "улун", "заварк", "чайная" -> "История чая";
            case "гитар", "музык", "аккорд", "песен", "рифф" -> "Перебор на гитаре";
            case "аниме", "манга", "магическ", "девчонк", "сериал" -> "Аниме про магических девчонок";
            case "кот", "кошк", "киса", "пушок", "мур" -> "Котики в интернете";
            case "прогулк", "ночн", "улца", "парк", "свеж" -> "Ночные прогулки";
            case "колледж", "учеб", "пара", "лекци", "зачёт", "экзамен" -> "Колледж / учёба";
            case "код", "программ", "java", "python", "бот", "телеграм" -> "Код / боты";
            case "рисов", "арт", "нарисов", "скетч" -> "Рисование";
            case "фото", "селфи", "камер", "объектив" -> "Фото / селфи";
            case "сон", "спать", "просыпа", "утро", "подъём" -> "Режим сна";
            case "еда", "кушать", "обед", "ужин", "завтрак" -> "Еда / готовка";
            case "друг", "подруг", "общ", "встреч", "звонок" -> "Общение с друзьями";
            case "мысл", "дума", "размышл", "идея", "мысль" -> "Мысли / идеи";
            default -> kw.substring(0, 1).toUpperCase() + kw.substring(1);
        };
    }

    private List<Map<String, Object>> buildScheduleBlocks(LocalTime now) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        int hour = now.getHour();
        DayOfWeek dow = LocalDate.now().getDayOfWeek();
        boolean weekend = dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;

        if (weekend) {
            blocks.add(block(0, 9, "Сон", "#1a1040"));
            blocks.add(block(9, 11, "Ленивое утро, кофе", "#4ecdc4"));
            blocks.add(block(11, 14, "Дома / прогулка", "#a855f7"));
            blocks.add(block(14, 18, "Друзья / дела / отдых", "#ff8c42"));
            blocks.add(block(18, 23, "Дома: игры, аниме, гитара", "#ff6b9d"));
            blocks.add(block(23, 24, "Ночные разговоры", "#ffd93d"));
        } else {
            blocks.add(block(0, 6, "Сон", "#1a1040"));
            blocks.add(block(6, 8, "Подъём, кофе, готовлюсь", "#4ecdc4"));
            blocks.add(block(8, 15, "Колледж: пары, перерывы", "#ff6b9d"));
            blocks.add(block(15, 17, "Дорога домой / дела", "#ff8c42"));
            blocks.add(block(17, 23, "Дома: гитара, игры, аниме", "#a855f7"));
            blocks.add(block(23, 24, "Ночные разговоры", "#ffd93d"));
        }

        for (Map<String, Object> b : blocks) {
            int start = (int) b.get("start");
            int end = (int) b.get("end");
            b.put("current", hour >= start && hour < end);
        }
        return blocks;
    }

    private Map<String, Object> block(int start, int end, String label, String color) {
        var b = new ConcurrentHashMap<String, Object>();
        b.put("start", start);
        b.put("end", end);
        b.put("label", label);
        b.put("color", color);
        b.put("current", false);
        return b;
    }

    private int computeMoodForHour(int h, LocalTime now) {
        int base;
        if (h >= 2 && h < 6) base = -20;
        else if (h < 8) base = 10;
        else if (h < 11) base = 40;
        else if (h < 14) base = 20;
        else if (h < 16) base = 10;
        else if (h < 19) base = 50;
        else if (h < 22) base = 60;
        else if (h < 24) base = 30;
        else base = 0;

        if (h == now.getHour() && mood != null) {
            base += (int) ((mood.energy() - 0.5) * 40);
        }
        return Math.clamp(base, -100, 100);
    }

    private int countDiaryEntriesForHour(int hour) {
        if (diary == null) return 0;
        int count = 0;
        for (DiaryEntry entry : diary.query(new double[0]).stream()
                .limit(500)
                .map(Diary.Match::entry)
                .toList()) {
            try {
                long ts = Long.parseLong(entry.id());
                LocalDateTime ldt = LocalDateTime.ofInstant(Instant.ofEpochSecond(ts), ZoneId.systemDefault());
                if (ldt.getHour() == hour) count++;
            } catch (NumberFormatException ignored) {}
        }
        return count;
    }

    // ───────────────────── HTML ─────────────────────

    private static final String INDEX_HTML = """
            <!doctype html>
            <html lang="ru">
            <head>
            <meta charset="utf-8">
            <title>Yukiko • Dashboard</title>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <link rel="preconnect" href="https://fonts.googleapis.com">
            <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
            <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&family=Montserrat:wght@700;800;900&display=swap" rel="stylesheet">
            <style>
              :root {
                --bg-start: #0a0e27;
                --bg-end: #1a1040;
                --pink: #ff6b9d;
                --teal: #4ecdc4;
                --yellow: #ffd93d;
                --orange: #ff8c42;
                --purple: #a855f7;
                --cyan: #06b6d4;
                --card: rgba(255,255,255,0.03);
                --card-border: rgba(255,255,255,0.08);
                --card-hover: rgba(255,255,255,0.06);
                --text: #f8fafc;
                --text-muted: #94a3b8;
                --glow-pink: rgba(255, 107, 157, 0.4);
                --glow-teal: rgba(78, 205, 196, 0.4);
                --glow-yellow: rgba(255, 217, 61, 0.4);
                --glow-orange: rgba(255, 140, 66, 0.4);
              }
              * { box-sizing: border-box; margin: 0; padding: 0; }
              @keyframes gradientShift {
                0%, 100% { background-position: 0% 50%; }
                50% { background-position: 100% 50%; }
              }
              @keyframes float { 0%, 100% { transform: translateY(0); } 50% { transform: translateY(-8px); } }
              @keyframes pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.6; } }
              @keyframes barGrow { from { height: 0; } to { height: var(--target-height); } }
              @keyframes slideIn { from { opacity: 0; transform: translateY(20px); } to { opacity: 1; transform: translateY(0); } }
              @keyframes shimmer { 0% { transform: translateX(-100%); } 100% { transform: translateX(100%); } }
              html, body { height: 100%; overflow: hidden; scroll-behavior: smooth; }
              body {
                font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
                background: linear-gradient(135deg, var(--bg-start) 0%, #12183a 50%, var(--bg-end) 100%);
                background-size: 200% 200%;
                animation: gradientShift 20s ease infinite;
                color: var(--text);
                line-height: 1.6;
                position: relative;
              }
              body::before {
                content: ''; position: fixed; inset: 0; pointer-events: none; z-index: 0;
                background:
                  radial-gradient(ellipse 60% 50% at 10% 10%, var(--glow-pink) 0%, transparent 60%),
                  radial-gradient(ellipse 50% 60% at 90% 90%, var(--glow-teal) 0%, transparent 60%),
                  radial-gradient(ellipse 40% 40% at 50% 5%, var(--glow-yellow) 0%, transparent 70%),
                  radial-gradient(ellipse 40% 40% at 5% 95%, var(--glow-orange) 0%, transparent 70%);
                opacity: 0.6;
              }
              body::after {
                content: ''; position: fixed; inset: 0; pointer-events: none; z-index: 0;
                background-image: url("data:image/svg+xml,%3Csvg viewBox='0 0 256 256' xmlns='http://www.w3.org/2000/svg'%3E%3Cfilter id='noise'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.9' numOctaves='4' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23noise)'/%3E%3C/svg%3E");
                opacity: 0.03;
              }
              #app { position: relative; z-index: 1; height: 100vh; display: flex; flex-direction: column; }
              /* Top bar */
              .topbar {
                display: flex; align-items: center; justify-content: space-between;
                padding: 16px 24px; background: rgba(10, 14, 39, 0.8); backdrop-filter: blur(20px);
                border-bottom: 1px solid var(--card-border); flex-shrink: 0;
              }
              .brand { display: flex; align-items: center; gap: 12px; }
              .brand-icon {
                width: 44px; height: 44px; border-radius: 12px;
                background: linear-gradient(135deg, var(--rose), var(--lavender));
                display: flex; align-items: center; justify-content: center;
                font-family: 'Montserrat', sans-serif; font-weight: 900; font-size: 18px; color: white;
                box-shadow: 0 0 30px var(--glow-rose), 0 0 60px var(--glow-lavender);
                animation: breathe 4s ease-in-out infinite;
              }
              .brand-text { font-family: 'Montserrat', sans-serif; font-weight: 800; font-size: 20px; letter-spacing: -0.5px; }
              .brand-text span { background: linear-gradient(135deg, var(--pink), var(--teal), var(--yellow)); -webkit-background-clip: text; -webkit-text-fill-color: transparent; background-clip: text; }
              .tabs { display: flex; gap: 4px; }
              .tab {
                padding: 10px 20px; border: none; background: transparent; color: var(--text-muted);
                font-family: 'Inter', sans-serif; font-weight: 600; font-size: 13px; cursor: pointer;
                border-radius: 10px; position: relative; overflow: hidden; transition: all 0.25s ease;
              }
              .tab::before { content: ''; position: absolute; inset: 0; background: linear-gradient(135deg, var(--pink), var(--teal)); opacity: 0; transition: opacity 0.25s; border-radius: 10px; }
              .tab { 
                  position: relative; 
                  overflow: hidden; 
                  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1); 
              }
              .tab::before { 
                  content: ''; 
                  position: absolute; 
                  inset: 0; 
                  background: linear-gradient(135deg, var(--rose), var(--amber)); 
                  opacity: 0; 
                  transition: opacity 0.3s ease; 
                  border-radius: 10px; 
              }
              .tab:hover { 
                  color: var(--text); 
                  transform: translateY(-2px); 
              }
              .tab:hover::before { 
                  opacity: 0.18; 
              }
              .tab:focus-visible { 
                  outline: 2px solid var(--border-focus); 
                  outline-offset: 2px; 
              }
              .tab.active { 
                  color: var(--rose); 
                  font-weight: 600; 
              }
              .tab.active::before { 
                  opacity: 0.22; 
              }
              .tab.active::after { 
                  content: ''; 
                  position: absolute; 
                  bottom: 0; 
                  left: 50%; 
                  transform: translateX(-50%); 
                  width: 70%; 
                  height: 3px; 
                  background: linear-gradient(90deg, var(--rose), var(--amber)); 
                  border-radius: 3px 3px 0 0; 
                  animation: slideIn 0.4s cubic-bezier(0.4, 0, 0.2, 1); 
              }
              .status-dots { display: flex; gap: 8px; align-items: center; }
              .dot { display: flex; align-items: center; gap: 6px; font-size: 12px; color: var(--text-muted); }
              .dot .led { width: 8px; height: 8px; border-radius: 50%; box-shadow: 0 0 10px currentColor; animation: pulse 2s ease-in-out infinite; }
              .dot.telegram .led { background: var(--teal); color: var(--teal); }
              .dot.process .led { background: var(--pink); color: var(--pink); }
              .dot.model .led { background: var(--yellow); color: var(--yellow); }
              /* Main content */
              main { 
                flex: 1; 
                overflow: auto; 
                padding: 24px; 
                display: grid; 
                grid-template-columns: 1fr; 
                gap: 24px; 
                transition: opacity 0.3s ease;
              }
              .panel { 
                  display: none; 
                  animation: fadeInUp 0.5s cubic-bezier(0.4, 0, 0.2, 1); 
                }
              .panel.active { 
                  display: block; 
                }
              /* Cards */
              .card {
                background: var(--card); border: 1px solid var(--card-border); border-radius: 20px;
                backdrop-filter: blur(20px); padding: 24px;
                transition: all 0.3s ease; position: relative; overflow: hidden;
              }
              .card::before { content: ''; position: absolute; inset: 0; background: linear-gradient(135deg, rgba(255,107,157,0.05), transparent 50%, rgba(78,205,196,0.05)); opacity: 0; transition: opacity 0.3s; pointer-events: none; }
              .card:hover { 
                  border-color: var(--border-focus); 
                  transform: translateY(-6px) scale(1.01); 
                  box-shadow: var(--shadow-medium), var(--shadow-glow); 
              }
              .card:hover::before { opacity: 1; }
              /* Grid layouts */
              .grid-3 { display: grid; grid-template-columns: repeat(3, 1fr); gap: 16px; }
              .grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 24px; }
              @media (max-width: 1000px) { .grid-3 { grid-template-columns: 1fr; } .grid-2 { grid-template-columns: 1fr; } }
              /* Typography */
              h1 { font-family: 'Montserrat', sans-serif; font-weight: 800; font-size: clamp(28px, 4vw, 40px); line-height: 1.1; letter-spacing: -1px; }
              h2 { font-family: 'Montserrat', sans-serif; font-weight: 700; font-size: 18px; color: var(--text-muted); text-transform: uppercase; letter-spacing: 1px; margin-bottom: 16px; }
              h3 { font-family: 'Inter', sans-serif; font-weight: 700; font-size: 15px; }
              .gradient-text { background: linear-gradient(135deg, var(--pink), var(--teal), var(--yellow)); -webkit-background-clip: text; -webkit-text-fill-color: transparent; background-clip: text; }
              .metric { text-align: center; padding: 20px; }
              .metric-value { font-family: 'Montserrat', sans-serif; font-weight: 800; font-size: 32px; line-height: 1; }
              .metric-label { font-size: 12px; color: var(--text-muted); text-transform: uppercase; letter-spacing: 1px; margin-top: 8px; }
              /* ===== PAGE 1: CHARACTER ===== */
              .character-card { display: grid; grid-template-columns: 1fr 380px; gap: 32px; align-items: start; }
              @media (max-width: 900px) { .character-card { grid-template-columns: 1fr; } }
              .mood-tag {
                display: inline-block; padding: 8px 20px; border-radius: 100px;
                background: linear-gradient(135deg, var(--pink), var(--purple));
                font-family: 'Montserrat', sans-serif; font-weight: 800; font-size: 14px; color: white;
                box-shadow: 0 0 20px var(--glow-pink); margin-bottom: 16px;
              }
              .activity-text { font-size: 16px; color: var(--text-muted); line-height: 1.7; margin-bottom: 24px; }
              .avatar-wrapper { position: relative; aspect-ratio: 1; border-radius: 24px; overflow: hidden; }
              .avatar-img { width: 100%; height: 100%; object-fit: cover; filter: drop-shadow(0 0 40px var(--glow-pink)); transition: transform 0.4s ease; }
              .avatar-wrapper:hover .avatar-img { transform: scale(1.02); }
              .avatar-glow { position: absolute; inset: -20px; background: radial-gradient(circle at center, var(--glow-pink) 0%, transparent 70%); pointer-events: none; animation: float 4s ease-in-out infinite; }

              .rel-avatar { width: 36px; height: 36px; border-radius: 50%; object-fit: cover; border: 2px solid var(--card-border); flex-shrink: 0; }
              .rel-avatar-large { width: 80px; height: 80px; border-radius: 50%; object-fit: cover; border: 3px solid var(--pink); box-shadow: 0 0 20px var(--glow-pink); margin-right: 16px; flex-shrink: 0; }
              .card-header { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
              /* ===== PAGE 2: CONTROL PANEL ===== */
              .equalizer { display: flex; align-items: flex-end; gap: 16px; height: 280px; padding: 20px 0; }
              .eq-bar { flex: 1; display: flex; flex-direction: column; align-items: center; gap: 12px; height: 100%; position: relative; }
              .eq-track { width: 100%; height: 100%; background: rgba(255,255,255,0.05); border-radius: 12px; position: relative; overflow: hidden; }
              .eq-fill { position: absolute; bottom: 0; left: 0; right: 0; border-radius: 12px 12px 0 0; transition: height 0.8s cubic-bezier(0.23, 1, 0.32, 1), background 0.3s; }
              .eq-fill::after { content: ''; position: absolute; top: 0; left: 0; right: 0; height: 4px; background: linear-gradient(90deg, transparent, rgba(255,255,255,0.3), transparent); border-radius: 12px 12px 0 0; }
              .eq-label { font-family: 'Montserrat', sans-serif; font-weight: 700; font-size: 11px; text-transform: uppercase; letter-spacing: 0.5px; }
              .eq-value { font-family: 'Montserrat', sans-serif; font-weight: 800; font-size: 20px; }
              .communication-card { margin-top: 24px; }
              .comm-text { font-size: 15px; line-height: 1.8; color: var(--text-muted); }
              .comm-text strong { color: var(--text); }
              .timeline-card { margin-top: 24px; }
              .timeline { display: flex; flex-direction: column; gap: 12px; }
              .timeline-row { display: flex; align-items: center; gap: 16px; height: 36px; }
              .timeline-label { width: 80px; font-size: 12px; color: var(--text-muted); text-align: right; font-weight: 500; }
              .timeline-track { flex: 1; height: 100%; background: rgba(255,255,255,0.05); border-radius: 8px; position: relative; overflow: hidden; }
              .timeline-block { position: absolute; top: 0; height: 100%; border-radius: 8px; display: flex; align-items: center; padding: 0 12px; font-size: 11px; font-weight: 600; color: white; white-space: nowrap; }
              .timeline-lines { display: flex; gap: 16px; margin-top: 20px; padding-top: 16px; border-top: 1px solid var(--card-border); }
              .line-graph { flex: 1; height: 100px; position: relative; }
              .line-svg { width: 100%; height: 100%; }
              .line-path { fill: none; stroke-width: 2.5; stroke-linecap: round; stroke-linejoin: round; filter: drop-shadow(0 0 8px currentColor); }
              .line-dots { position: absolute; inset: 0; }
              .line-dot { position: absolute; width: 8px; height: 8px; border-radius: 50%; transform: translate(-50%, -50%); box-shadow: 0 0 10px currentColor; transition: transform 0.2s; }
              .line-dot:hover { transform: translate(-50%, -50%) scale(1.5); z-index: 10; }
              .line-legend { display: flex; flex-wrap: wrap; gap: 16px; margin-top: 12px; font-size: 12px; }
              .legend-item { display: flex; align-items: center; gap: 6px; color: var(--text-muted); }
              .legend-color { width: 10px; height: 10px; border-radius: 50%; }
              /* ===== PAGE 3: THOUGHTS & INTERESTS ===== */
              .thoughts-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 24px; }
              @media (max-width: 900px) { .thoughts-grid { grid-template-columns: 1fr; } }
              .thought-card { background: var(--card); border: 1px solid var(--card-border); border-radius: 16px; padding: 16px; margin-bottom: 12px; transition: all 0.25s; position: relative; }
              .thought-card:hover { 
                border-color: var(--border-focus); 
                transform: translateX(6px); 
                box-shadow: var(--shadow-soft); 
            }
              .thought-header { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; }
              .thought-time { font-family: 'Montserrat', sans-serif; font-weight: 700; font-size: 12px; color: var(--text-muted); }
              .thought-emotion { padding: 4px 10px; border-radius: 100px; font-size: 11px; font-weight: 600; text-transform: uppercase; letter-spacing: 0.5px; }
              .thought-text { font-size: 14px; line-height: 1.6; color: var(--text); }
              .interest-bar { margin-bottom: 16px; }
              .interest-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
              .interest-name { font-weight: 600; font-size: 14px; }
              .interest-value { font-family: 'Montserrat', sans-serif; font-weight: 800; font-size: 18px; }
              .interest-track { height: 10px; background: rgba(255,255,255,0.08); border-radius: 5px; overflow: hidden; position: relative; }
              .interest-fill { height: 100%; border-radius: 5px; transition: width 1.2s cubic-bezier(0.23, 1, 0.32, 1); position: relative; }
              .interest-fill::after { content: ''; position: absolute; top: 0; left: 0; right: 0; bottom: 0; background: linear-gradient(90deg, transparent 30%, rgba(255,255,255,0.2) 50%, transparent 70%); animation: shimmer 2s infinite; }
              @keyframes shimmer { 0% { transform: translateX(-100%); } 100% { transform: translateX(100%); } }
              /* ===== PAGE 4: STATS ===== */
              .charts-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 24px; }
              @media (max-width: 1100px) { .charts-grid { grid-template-columns: 1fr; } }
              .chart-card { background: var(--card); border: 1px solid var(--card-border); border-radius: 20px; padding: 24px; }
              .chart-title { font-family: 'Montserrat', sans-serif; font-weight: 700; font-size: 16px; margin-bottom: 20px; }
              .chart { height: 300px; position: relative; }
              .chart-svg { width: 100%; height: 100%; }
              .chart-bar { cursor: pointer; transition: all 0.2s; }
              .chart-bar:hover { filter: brightness(1.2); }
              .chart-bar rect { rx: 4px 4px 0 0; }
              .chart-x-label { font-size: 11px; fill: var(--text-muted); text-anchor: middle; font-family: 'Inter', sans-serif; }
              .chart-y-grid { stroke: rgba(255,255,255,0.05); stroke-width: 1; }
              .chart-tooltip { position: absolute; pointer-events: none; background: rgba(10,14,39,0.95); border: 1px solid var(--card-border); border-radius: 12px; padding: 12px 16px; font-size: 12px; z-index: 100; opacity: 0; transition: opacity 0.15s; backdrop-filter: blur(10px); }
              .chart-tooltip.visible { opacity: 1; }
              .chart-tooltip-row { display: flex; justify-content: space-between; gap: 16px; margin: 4px 0; }
              .chart-tooltip-label { color: var(--text-muted); }
              .chart-tooltip-value { font-family: 'Montserrat', sans-serif; font-weight: 700; }
              .chart-legend { display: flex; flex-wrap: wrap; gap: 16px; margin-top: 16px; padding-top: 16px; border-top: 1px solid var(--card-border); }
              .legend-item { display: flex; align-items: center; gap: 8px; font-size: 12px; color: var(--text-muted); cursor: pointer; transition: color 0.2s; }
              .legend-item:hover { color: var(--text); }
              .legend-item.inactive { opacity: 0.4; }
              .legend-dot { width: 10px; height: 10px; border-radius: 50%; }
              /* Relationships */
              .relationships-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 12px; }
              @media (max-width: 700px) { .relationships-grid { grid-template-columns: 1fr; } }
              .rel-card { background: var(--card); border: 1px solid var(--card-border); border-radius: 16px; padding: 16px; transition: all 0.3s ease; cursor: pointer; }
              .rel-card:hover { 
                border-color: var(--border-focus); 
                transform: translateY(-4px); 
                box-shadow: var(--shadow-medium); 
            }
              .rel-avatar { width: 36px; height: 36px; border-radius: 50%; object-fit: cover; border: 2px solid var(--card-border); flex-shrink: 0; }
              .rel-avatar-large { width: 80px; height: 80px; border-radius: 50%; object-fit: cover; border: 3px solid var(--pink); box-shadow: 0 0 20px var(--glow-pink); margin-right: 16px; flex-shrink: 0; }
              .card-header { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
              .badge { display: inline-flex; align-items: center; gap: 4px; padding: 2px 8px; border-radius: 999px; font-size: 11px; font-weight: 500; }
              .badge.ok { background: rgba(107, 210, 138, 0.15); color: var(--ok); border: 1px solid rgba(107, 210, 138, 0.3); }
              .badge.warn { background: rgba(241, 194, 122, 0.15); color: var(--warn); border: 1px solid rgba(241, 194, 122, 0.3); }
              .badge.err { background: rgba(255, 125, 125, 0.15); color: var(--err); border: 1px solid rgba(255, 125, 125, 0.3); }
              .badge.muted { background: rgba(125, 134, 148, 0.15); color: var(--muted); border: 1px solid rgba(125, 134, 148, 0.3); }
              .badge.accent { background: rgba(138, 180, 255, 0.15); color: var(--accent); border: 1px solid rgba(138, 180, 255, 0.3); }
              .row { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 10px; margin-top: 8px; }
              .field { background: var(--panel2); border: 1px solid var(--border); border-radius: 6px; padding: 8px 10px; }
              .field-label { color: var(--muted); font-size: 11px; text-transform: uppercase; letter-spacing: 0.5px; }
              .field-value { color: var(--fg); font-size: 13px; margin-top: 2px; word-break: break-word; }
              .notes-list { display: flex; flex-direction: column; gap: 6px; margin-top: 8px; }
              .note-item { background: var(--panel2); border: 1px solid var(--border); border-radius: 6px; padding: 8px 10px; font-size: 12px; }
              .intimacy-bar { height: 6px; background: var(--border); border-radius: 3px; overflow: hidden; margin-top: 4px; }
              .intimacy-fill { height: 100%; border-radius: 3px; transition: width 0.3s; }
              .intimacy-fill.high { background: linear-gradient(90deg, var(--ok), var(--accent)); }
              .intimacy-fill.med { background: linear-gradient(90deg, var(--warn), var(--accent2)); }
              .intimacy-fill.low { background: linear-gradient(90deg, var(--err), var(--warn)); }
              .loading { display: inline-flex; gap: 4px; align-items: center; color: var(--muted); }
              .spinner { width: 14px; height: 14px; border: 2px solid var(--border); border-top-color: var(--accent); border-radius: 50%; animation: spin 1s linear infinite; }
              @keyframes spin { to { transform: rotate(360deg); } }
              /* Scrollbar */
              ::-webkit-scrollbar { width: 8px; height: 8px; }
              ::-webkit-scrollbar-track { background: transparent; }
              ::-webkit-scrollbar-thumb { background: linear-gradient(180deg, var(--pink), var(--teal)); border-radius: 4px; border: 2px solid var(--bg-start); }
              ::-webkit-scrollbar-thumb:hover { background: linear-gradient(180deg, var(--teal), var(--pink)); }
              /* Reduced motion */
              @media (prefers-reduced-motion: reduce) { *, *::before, *::after { animation-duration: 0.01ms !important; transition-duration: 0.01ms !important; } }
            </style>
            </head>
            <body>
            <div id="app">
              <header class="topbar">
                <div class="brand">
                  <div class="brand-icon">Y</div>
                  <div class="brand-text">Yukiko <span>Dashboard</span></div>
                </div>
                <nav class="tabs">
                  <button class="tab active" data-panel="character">Персонаж</button>
                  <button class="tab" data-panel="control">Пульт</button>
                  <button class="tab" data-panel="thoughts">Мысли</button>
                  <button class="tab" data-panel="relationships">Отношения</button>
                  <button class="tab" data-panel="stats">Статистика</button>
                </nav>
                <div class="status-dots">
                  <div class="dot process"><span class="led" id="led-process"></span><span>Процесс</span></div>
                  <div class="dot telegram"><span class="led" id="led-telegram"></span><span>Telegram</span></div>
                  <div class="dot model"><span class="led" id="led-model"></span><span>Модель</span></div>
                </div>
              </header>
              <main>
                <!-- PAGE 1: CHARACTER -->
                <section class="panel active" id="panel-character">
                  <div class="card character-card">
                    <div>
                      <div class="mood-tag" id="mood-tag">загрузка…</div>
                      <p class="activity-text" id="activity-text">загрузка…</p>
                      <div class="grid-3">
                        <div class="metric">
                          <div class="metric-value gradient-text" id="metric-lastmsg">—</div>
                          <div class="metric-label">Писала назад</div>
                        </div>
                        <div class="metric">
                          <div class="metric-value gradient-text" id="metric-yourmood">—</div>
                          <div class="metric-label">Твоё настроение</div>
                        </div>
                        <div class="metric">
                          <div class="metric-value gradient-text" id="metric-model">—</div>
                          <div class="metric-label">Отвечает модель</div>
                        </div>
                      </div>
                    </div>
                    <div class="avatar-wrapper">
                      <div class="avatar-glow"></div>
                      <img class="avatar-img" id="avatar-img" src="" alt="Yukiko" style="display:none;">
                    </div>
                  </div>
                </section>

                <!-- PAGE 2: CONTROL PANEL -->
                <section class="panel" id="panel-control">
                  <div class="card">
                    <h2>Эквалайзер настроения</h2>
                    <div class="equalizer" id="equalizer"></div>
                  </div>
                  <div class="card communication-card">
                    <h2>Общение</h2>
                    <p class="comm-text" id="comm-text">загрузка…</p>
                  </div>
                  <div class="card timeline-card">
                    <h2>День</h2>
                    <div class="timeline" id="timeline-schedule"></div>
                    <div class="timeline-lines">
                      <div class="line-graph">
                        <svg class="line-svg" id="mood-line" viewBox="0 0 1000 100" preserveAspectRatio="none"></svg>
                        <div class="line-dots" id="mood-dots"></div>
                      </div>
                      <div class="line-graph">
                        <svg class="line-svg" id="thoughts-line" viewBox="0 0 1000 100" preserveAspectRatio="none"></svg>
                        <div class="line-dots" id="thoughts-dots"></div>
                      </div>
                    </div>
                    <div class="line-legend" id="line-legend"></div>
                  </div>
                </section>

                <!-- PAGE 3: THOUGHTS & INTERESTS -->
                <section class="panel" id="panel-thoughts">
                  <div class="thoughts-grid">
                    <div>
                      <h2>О чём она думает</h2>
                      <div id="thoughts-list"></div>
                    </div>
                    <div>
                      <h2>Чем увлечена</h2>
                      <div id="interests-list"></div>
                    </div>
                  </div>
                </section>

                <!-- PAGE: RELATIONSHIPS -->
                <section class="panel" id="panel-relationships">
                  <div class="panel-header">
                    <h2>Отношения</h2>
                    <div style="display: flex; gap: 12px; align-items: center; flex-wrap: wrap;">
                      <input type="text" id="rel-filter" placeholder="Фильтр по имени или ID" style="flex: 1; max-width: 300px;">
                      <button id="rel-refresh" class="primary">Обновить</button>
                      <span class="muted">всего: <b id="rel-count">0</b></span>
                    </div>
                  </div>
                  <div class="panel-body">
                    <div id="rel-grid" class="relationships-grid"></div>
                    <div id="rel-detail" style="display: none;"></div>
                  </div>
                </section>

                <!-- PAGE 4: STATS -->
                <section class="panel" id="panel-stats">
                  <div class="charts-grid">
                    <div class="chart-card">
                      <div class="chart-title">Ответы по дням</div>
                      <div class="chart">
                        <svg class="chart-svg" id="replies-chart" viewBox="0 0 1000 300" preserveAspectRatio="none"></svg>
                        <div class="chart-tooltip" id="replies-tooltip"></div>
                      </div>
                      <div class="chart-legend" id="replies-legend"></div>
                    </div>
                    <div class="chart-card">
                      <div class="chart-title">Токены по моделям (тыс.)</div>
                      <div class="chart">
                        <svg class="chart-svg" id="tokens-chart" viewBox="0 0 1000 300" preserveAspectRatio="none"></svg>
                        <div class="chart-tooltip" id="tokens-tooltip"></div>
                      </div>
                      <div class="chart-legend" id="tokens-legend"></div>
                    </div>
                  </div>
                </section>
              </main>
            </div>
            <script>
              // ─── State & Config ───
              const COLORS = {
                pink: '#ff6b9d', teal: '#4ecdc4', yellow: '#ffd93d',
                orange: '#ff8c42', purple: '#a855f7', cyan: '#06b6d4',
                red: '#f87171', green: '#4ade80', blue: '#60a5fa'
              };
              const MOOD_COLORS = ['#ff6b9d', '#4ecdc4', '#ffd93d', '#ff8c42', '#a855f7', '#06b6d4'];
              const REPLY_LEGEND = [
                { key: 'you', label: 'Тебе', color: '#ff6b9d' },
                { key: 'groups', label: 'В группах', color: '#4ecdc4' },
                { key: 'others', label: 'Другим', color: '#ff8c42' }
              ];
              const TOKEN_LEGEND = [
                { key: 'ds', label: 'ds', color: '#ff6b9d' },
                { key: 'local', label: 'local', color: '#4ecdc4' },
                { key: 'free', label: 'free', color: '#ffd93d' },
                { key: 'paid', label: 'paid', color: '#ff8c42' },
                { key: 'ds2', label: 'ds2', color: '#a855f7' },
                { key: 'grp', label: 'grp', color: '#06b6d4' }
              ];
              const EQ_CONFIG = [
                { id: 'energy', label: 'Энергия', color: '#ff6b9d' },
                { id: 'communication', label: 'Общение', color: '#4ecdc4' },
                { id: 'curiosity', label: 'Любопытство', color: '#ffd93d' },
                { id: 'tension', label: 'Напряжение', color: '#ff8c42' },
                { id: 'affection', label: 'Симпатия к тебе', color: '#a855f7' },
                { id: 'trust', label: 'Доверие', color: '#06b6d4' },
                { id: 'irritation', label: 'Раздражение', color: '#f87171' }
              ];

              // ─── Utils ───
              const $ = (sel, ctx = document) => ctx.querySelector(sel);
              const $$ = (sel, ctx = document) => Array.from(ctx.querySelectorAll(sel));
              const fmtTime = (ms) => {
                const s = Math.floor(ms / 1000);
                const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60);
                return (h > 0 ? h + 'ч ' : '') + m + 'м';
              };
              function lerp(a, b, t) { return a + (b - a) * t; }
              function clamp(v, min, max) { return Math.max(min, Math.min(max, v)); }

              // ─── Tab switching ───
              $$('.tab').forEach(btn => {
                btn.addEventListener('click', () => {
                  $$('.tab').forEach(b => b.classList.remove('active'));
                  $$('.panel').forEach(p => p.classList.remove('active'));
                  btn.classList.add('active');
                  $('#panel-' + btn.dataset.panel).classList.add('active');
                });
              });

              // ─── Status polling ───
              async function fetchStatus() {
                try {
                  const r = await fetch('/api/status');
                  const j = await r.json();
                  updateStatusLED('led-process', j.processAlive);
                  updateStatusLED('led-telegram', j.telegramOnline);
                  updateStatusLED('led-model', true);
                } catch (e) { /* ignore */ }
              }
              function updateStatusLED(id, ok, label) {
                const el = $('#' + id);
                if (el) { 
                    el.style.background = ok ? '' : '#666'; 
                    el.style.boxShadow = ok ? '0 0 10px currentColor' : 'none'; 
                    if (label) el.title = label;
                }
              }
              fetchStatus(); setInterval(fetchStatus, 5000);

              // ─── Page 1: Character ───
              async function loadCharacter() {
                $('#mood-tag').textContent = 'загрузка…';
                $('#activity-text').textContent = 'загрузка…';
                try {
                  const [moodR, photoR] = await Promise.all([
                    fetch('/api/mood').then(r => r.json()),
                    fetch('/api/my-photo').then(r => r.blob()).catch(() => null)
                  ]);
                  const m = moodR;
                  $('#mood-tag').textContent = m.moodTag || 'нежное';
                  $('#activity-text').textContent = m.activity || 'Дома: гитара, игры, аниме, ночные разговоры';
                  $('#metric-lastmsg').textContent = m.lastMessageAgo || '23 мин';
                  $('#metric-yourmood').textContent = m.yourMood || 'ласковый';
                  $('#metric-model').textContent = m.model || 'ds';
                  updateStatusLED('led-model', true, m.model || 'ds');
                  if (photoR) {
                    const img = $('#avatar-img');
                    img.src = URL.createObjectURL(photoR);
                    img.style.display = '';
                  }
                } catch (e) { console.warn('Character load failed', e); }
              }
              loadCharacter();

              // ─── Page 2: Control Panel ───
              async function loadControl() {
                try {
                  const [moodR, schedR] = await Promise.all([
                    fetch('/api/mood').then(r => r.json()),
                    fetch('/api/schedule').then(r => r.json())
                  ]);
                  renderEqualizer(moodR);
                  renderSchedule(schedR);
                  renderMoodLine(schedR.moodLine || []);
                  renderThoughtsLine(schedR.thoughtEvents || []);
                  $('#comm-text').innerHTML = 'Сейчас <strong>дома</strong>, в спокойной обстановке. Отвечает на сообщения с задержкой 1-2 минуты, предпочитает голосовые когда лень печатать. В группах редко пишет, только если обратились напрямую.';
                } catch (e) { console.warn('Control load failed', e); }
              }
              function renderEqualizer(mood) {
                const container = $('#equalizer');
                container.innerHTML = EQ_CONFIG.map((eq, i) => {
                  const val = mood[eq.id] ?? 50;
                  const delay = i * 80;
                  return `
                    <div class="eq-bar" style="--delay: ${delay}ms;">
                      <div class="eq-track">
                        <div class="eq-fill" style="--target-height: ${val}%; height: 0%; background: ${eq.color};"></div>
                      </div>
                      <div class="eq-label" style="color: ${eq.color};">${eq.label}</div>
                      <div class="eq-value" style="color: ${eq.color};">${val}</div>
                    </div>`;
                }).join('');
                requestAnimationFrame(() => {
                  $$('.eq-fill', container).forEach((el, i) => {
                    const target = el.style.getPropertyValue('--target-height');
                    setTimeout(() => { el.style.height = target; }, i * 80 + 100);
                  });
                });
              }
              function renderSchedule(sched) {
                const container = $('#timeline-schedule');
                const blocks = sched.schedule || [];
                const now = new Date();
                const currentHour = now.getHours() + now.getMinutes() / 60;
                
                container.innerHTML = blocks.map(b => {
                  const widthPct = ((b.end - b.start) / 24) * 100;
                  const leftPct = (b.start / 24) * 100;
                  const isCurrent = currentHour >= b.start && currentHour < b.end;
                  return `
                    <div class="timeline-row" ${isCurrent ? 'style="background: rgba(255,107,157,0.08); border-radius: 8px; padding: 4px 8px; margin: 2px -8px;"' : ''}>
                      <span class="timeline-label">${String(b.start).padStart(2,'0')}:00</span>
                      <div class="timeline-track">
                        <div class="timeline-block" style="left: ${leftPct}%; width: ${widthPct}%; background: ${b.color};" title="${b.label} (${b.start}:00-${b.end}:00)">
                          <span style="font-size: 10px; font-weight: 600; text-shadow: 0 0 4px rgba(0,0,0,0.5);">${b.label}</span>
                          ${isCurrent ? '<span style="position: absolute; right: -20px; top: 50%; transform: translateY(-50%); font-size: 10px; color: var(--pink);">► Сейчас</span>' : ''}
                        </div>
                      </div>
                    </div>`;
                }).join('');
              }
              function renderLineGraph(svgId, dotsId, data, color, label) {
                const svg = $('#' + svgId);
                const dotsContainer = $('#' + dotsId);
                if (!data.length) return;
                const max = Math.max(...data.map(Math.abs), 1);
                const points = data.map((v, i) => {
                  const x = (i / (data.length - 1)) * 1000;
                  const y = 50 - (v / max) * 45;
                  return `${x},${y}`;
                }).join(' ');
                svg.innerHTML = `<path class="line-path" d="M${points}" stroke="${color}" />`;
                for (let g = 0; g <= 4; g++) {
                  const gy = g * 25;
                  svg.innerHTML += `<line x1="0" y1="${gy}" x2="1000" y2="${gy}" class="chart-y-grid" />`;
                }
                dotsContainer.innerHTML = data.map((v, i) => {
                  const x = (i / (data.length - 1)) * 1000;
                  const y = 50 - (v / max) * 45;
                  return `<div class="line-dot" style="left:${x}px; top:${y}px; background:${color}; --label:${label}; --hour:${i}; --value:${v};" title="${label}: ${i}:00 — ${v}"></div>`;
                }).join('');
              }
              function renderMoodLine(data) { renderLineGraph('mood-line', 'mood-dots', data, '#ff6b9d', 'Настроение'); }
              function renderThoughtsLine(data) { renderLineGraph('thoughts-line', 'thoughts-dots', data, '#ffd93d', 'Мысли'); }
              function renderLineLegend() {
                const container = $('#line-legend');
                container.innerHTML = `
                  <div class="legend-item"><span class="legend-color" style="background:#ff6b9d"></span>Настроение</div>
                  <div class="legend-item"><span class="legend-color" style="background:#ffd93d"></span>Активность мыслей</div>`;
              }
              renderLineLegend();

              // ─── Page 3: Thoughts & Interests ───
              async function loadThoughts() {
                try {
                  const [thoughtsR, interestsR] = await Promise.all([
                    fetch('/api/thoughts').then(r => r.json()),
                    fetch('/api/interests').then(r => r.json())
                  ]);
                  renderThoughts(thoughtsR);
                  renderInterests(interestsR);
                } catch (e) { console.warn('Thoughts load failed', e); }
              }
              function renderThoughts(list) {
                const container = $('#thoughts-list');
                if (!list.length) {
                    container.innerHTML = '<div class="muted" style="padding: 20px; text-align: center;">Пока мыслей нет</div>';
                    return;
                }
                
                // Группируем по типам
                const byType = { diary: [], memory: [], chat: [] };
                list.forEach(t => {
                    if (t.source === 'diary' || t.emotion === 'грусть' || t.emotion === 'радость' || t.emotion === 'злость' || t.emotion === 'тревога') {
                        byType.diary.push(t);
                    } else if (t.emotion === 'внимание' || t.emotion === 'любопытство' || t.emotion === 'вдохновение') {
                        byType.memory.push(t);
                    } else {
                        byType.chat.push(t);
                    }
                });
                
                const sections = [
                    { key: 'diary', title: '📓 Дневник и чувства', items: byType.diary, color: '#ff6b9d' },
                    { key: 'memory', title: '🧠 Внимание и интересы', items: byType.memory, color: '#4ecdc4' },
                    { key: 'chat', title: '💬 Из чатов', items: byType.chat, color: '#ffd93d' }
                ];

                container.innerHTML = sections.map(section => {
                    if (!section.items.length) return '';
                    return `
                        <div class="thought-section" style="margin-bottom: 24px;">
                            <h3 style="color: ${section.color}; margin-bottom: 12px; font-size: 14px; font-weight: 600; text-transform: uppercase; letter-spacing: 0.5px;">${section.title}</h3>
                            <div class="thought-list">
                                ${section.items.map((t, i) => `
                                    <div class="thought-card" style="animation-delay: ${i * 60}ms; background: var(--card); border: 1px solid var(--card-border); border-radius: 12px; padding: 14px 16px; margin-bottom: 10px; transition: all 0.2s; border-left: 3px solid ${section.color};">
                                        <div style="display: flex; align-items: center; gap: 10px; margin-bottom: 8px; flex-wrap: wrap;">
                                            <span class="thought-time" style="font-family: 'Montserrat', sans-serif; font-weight: 600; font-size: 11px; color: var(--text-muted);">${t.time}</span>
                                            <span class="thought-emotion" style="background: ${section.color}22; color: ${section.color}; border: 1px solid ${section.color}44; padding: 3px 8px; border-radius: 100px; font-size: 10px; font-weight: 600; text-transform: uppercase; letter-spacing: 0.3px;">${t.emotion}</span>
                                            ${t.location ? `<span style="font-size: 11px; color: var(--text-muted); background: var(--panel2); padding: 2px 8px; border-radius: 4px;">📍 ${t.location}</span>` : ''}
                                        </div>
                                        <p class="thought-text" style="font-size: 13px; line-height: 1.6; color: var(--text); margin: 0;">${t.text}</p>
                                    </div>
                                `).join('')}
                            </div>
                        </div>
                    `;
                }).join('');
              }
              function renderInterests(list) {
                const container = $('#interests-list');
                if (!Array.isArray(list) || !list.length) {
                  container.innerHTML = '<div class="muted" style="padding: 20px; text-align: center;">Пока интересов нет</div>';
                  return;
                }
                const maxValInterests = Math.max(...list.map(l => l.value), 1);
                container.innerHTML = list.map((item, i) => {
                  const pct = (item.value / maxValInterests) * 100;
                  return `
                    <div class="interest-bar">
                      <div class="interest-header">
                        <span class="interest-name">${item.name}</span>
                        <span class="interest-value gradient-text" style="color: ${item.color};">${item.value}</span>
                      </div>
                      <div class="interest-track">
                        <div class="interest-fill" style="width: 0%; background: ${item.color}; --target-width: ${pct}%;"></div>
                      </div>
                    </div>`;
                }).join('');
                requestAnimationFrame(() => {
                  $$('.interest-fill', container).forEach((el, i) => {
                    const target = el.style.getPropertyValue('--target-width');
                    setTimeout(() => { el.style.width = target; }, i * 120 + 200);
                  });
                });
              }

              // ─── Page 4: Stats ───
              async function loadStats() {
                try {
                  const r = await fetch('/api/stats');
                  const data = await r.json();
                  renderStackedChart('replies-chart', 'replies-tooltip', 'replies-legend', data.dates, data.replies, REPLY_LEGEND);
                  renderStackedChart('tokens-chart', 'tokens-tooltip', 'tokens-legend', data.dates, data.tokens, TOKEN_LEGEND);
                } catch (e) { console.warn('Stats load failed', e); }
              }
              function renderStackedChart(svgId, tooltipId, legendId, dates, series, legend) {
                const svg = $('#' + svgId);
                const tooltip = $('#' + tooltipId);
                const legendContainer = $('#' + legendId);
                if (!dates.length || !series.length) return;
                const n = dates.length;
                const barW = 600 / n;
                const gap = barW * 0.3;
                const bw = barW - gap;
                let maxValReplies = Math.max(...series.map(s => s.reduce((a,b)=>a+b,0)), 1);
                const chartH = 260;
                const bottom = 30;
                let svgContent = '';
                for (let g = 0; g <= 5; g++) {
                  const y = bottom + (chartH * g / 5);
                  svgContent += `<line x1="0" y1="${y}" x2="1000" y2="${y}" class="chart-y-grid" />`;
                }
                series.forEach((stack, i) => {
                  let yBase = bottom + chartH;
                  stack.forEach((val, si) => {
                    const h = (val / maxValReplies) * chartH;
                    const y = yBase - h;
                    const x = 200 + i * (barW + gap) + si * 0;
                    svgContent += `
                      <g class="chart-bar" data-date="${dates[i]}" data-series="${legend[si].key}" data-value="${val}">
                        <rect x="${x}" y="${y}" width="${bw}" height="${h}" fill="${legend[si].color}" rx="3 3 0 0" />
                      </g>`;
                    yBase = y;
                  });
                });
                dates.forEach((d, i) => {
                  const x = 200 + i * (barW + gap) + bw / 2;
                  svgContent += `<text class="chart-x-label" x="${x}" y="${bottom + chartH + 20}">${d}</text>`;
                });
                svg.innerHTML = svgContent;
                $$('.chart-bar', svg).forEach(g => {
                  g.addEventListener('mousemove', e => {
                    const date = g.dataset.date;
                    const seriesKey = g.dataset.series;
                    const value = g.dataset.value;
                    const legendItem = legend.find(l => l.key === seriesKey);
                    tooltip.innerHTML = `
                      <div style="font-weight:700; margin-bottom:8px; color:${legendItem.color}">${date}</div>
                      <div class="chart-tooltip-row"><span class="chart-tooltip-label">${legendItem.label}</span><span class="chart-tooltip-value" style="color:${legendItem.color}">${Number(value).toLocaleString()}</span></div>
                    `;
                    tooltip.classList.add('visible');
                    const rect = svg.getBoundingClientRect();
                    tooltip.style.left = (e.clientX - rect.left + 16) + 'px';
                    tooltip.style.top = (e.clientY - rect.top - 80) + 'px';
                  });
                  g.addEventListener('mouseleave', () => tooltip.classList.remove('visible'));
                });
                legendContainer.innerHTML = legend.map(l => `
                  <div class="legend-item" data-series="${l.key}">
                    <span class="legend-dot" style="background:${l.color}"></span>${l.label}
                  </div>`).join('');
                $$('.legend-item', legendContainer).forEach(item => {
                  item.addEventListener('click', () => {
                    item.classList.toggle('inactive');
                    const key = item.dataset.series;
                    $$('.chart-bar', svg).forEach(bar => {
                      if (bar.dataset.series === key) bar.style.display = item.classList.contains('inactive') ? 'none' : '';
                    });
                  });
                });
              }

              // ─── Page: Relationships ───
              async function loadRelationships() {
                try {
                  const r = await fetch('/api/relationships');
                  const rels = await r.json();
                  window.relationshipsData = rels;
                  renderRelationships(rels);
                } catch (e) { console.warn('Relationships load failed', e); }
              }
              function renderRelationships(list) {
                const container = $('#rel-grid');
                const filter = $('#rel-filter').value.trim().toLowerCase();
                const filtered = list.filter(p =>
                  !filter || (p.name && p.name.toLowerCase().includes(filter)) || String(p.userId).includes(filter)
                );
                $('#rel-count').textContent = filtered.length;
                if (!filtered.length) {
                  container.innerHTML = '<div class="empty">Нет людей, подходящих под фильтр</div>';
                  return;
                }
                container.innerHTML = filtered.map(p => {
                  const cold = p.coldUntilHours > 0;
                  const intimacy = p.intimacy ?? 0;
                  const intClass = intimacy >= 55 ? 'high' : intimacy >= 10 ? 'med' : 'low';
                  const intLabel = intimacy >= 80 ? 'very close' : intimacy >= 55 ? 'friends' : intimacy >= 30 ? 'acquaintances' : intimacy >= 10 ? 'barely know' : 'stranger';
                  const genderLabel = p.genderLabel || 'unknown';
                  const gender = p.gender || '';
                  const timeAgo = p.lastInteractionAgo
                    ? (p.lastInteractionAgo < 60 ? p.lastInteractionAgo + ' мин назад' : p.lastInteractionAgo < 1440 ? Math.floor(p.lastInteractionAgo / 60) + ' ч назад' : Math.floor(p.lastInteractionAgo / 1440) + ' дн назад')
                    : 'никогда';
                  const photoUrl = `/api/user-photo/${p.userId}`;
                  return `
                    <div class="card rel-card" data-user-id="${p.userId}" style="cursor: pointer;">
                      <div class="card-header">
                        <img src="${photoUrl}" alt="" class="rel-avatar" onerror="this.style.display='none'" loading="lazy">
                        <span class="card-title">${p.name || 'unknown'} <span class="muted">(${p.userId})</span></span>
                        ${cold ? '<span class="badge err">холодно ' + p.coldUntilHours + 'ч</span>' : ''}
                      </div>
                      <div class="row">
                        <div class="field"><div class="field-label">Близость</div>
                          <div class="field-value">${intimacy} — ${intLabel}</div>
                          <div class="intimacy-bar"><div class="intimacy-fill ${intClass}" style="width:${intimacy}%"></div></div>
                        </div>
                        <div class="field"><div class="field-label">Пол</div>
                          <div class="field-value">${genderLabel} ${gender ? '(' + gender + ')' : ''}</div>
                        </div>
                        <div class="field"><div class="field-label">Последний контакт</div>
                          <div class="field-value">${timeAgo}</div>
                        </div>
                      </div>
                      ${p.notes && p.notes.length ? `
                        <div class="notes-list">
                          ${p.notes.map(n => '<div class="note-item">• ' + n.replace(/</g,'<') + '</div>').join('')}
                        </div>
                      ` : '<div class="muted" style="margin-top:8px">нет заметок</div>'}
                    </div>`;
                }).join('');
                $$('.rel-card', container).forEach(card => {
                  card.addEventListener('click', () => showPersonDetail(card.dataset.userId));
                });
              }
              function showPersonDetail(userId) {
                const p = window.relationshipsData.find(x => x.userId == userId);
                if (!p) return;
                const cold = p.coldUntilHours > 0;
                const intimacy = p.intimacy ?? 0;
                const intLabel = intimacy >= 80 ? 'very close' : intimacy >= 55 ? 'friends' : intimacy >= 30 ? 'acquaintances' : intimacy >= 10 ? 'barely know' : 'stranger';
                const timeAgo = p.lastInteractionAgo
                  ? (p.lastInteractionAgo < 60 ? p.lastInteractionAgo + ' мин назад' : p.lastInteractionAgo < 1440 ? Math.floor(p.lastInteractionAgo / 60) + ' ч назад' : Math.floor(p.lastInteractionAgo / 1440) + ' дн назад')
                  : 'никогда';
                const photoUrl = `/api/user-photo/${p.userId}`;
                const detail = $('#rel-detail');
                detail.style.display = 'block';
                detail.innerHTML = `
                  <div class="card" style="margin-bottom: 16px;">
                    <div class="card-header">
                      <img src="${photoUrl}" alt="" class="rel-avatar-large" onerror="this.style.display='none'">
                      <span class="card-title">${p.name || 'unknown'} <span class="muted">(${p.userId})</span></span>
                      ${cold ? '<span class="badge err">холодно ' + p.coldUntilHours + 'ч</span>' : ''}
                    </div>
                    <div class="row">
                      <div class="field"><div class="field-label">Близость</div>
                        <div class="field-value">${intimacy} — ${intLabel}</div>
                        <div class="intimacy-bar"><div class="intimacy-fill ${intimacy >= 55 ? 'high' : intimacy >= 10 ? 'med' : 'low'}" style="width:${intimacy}%"></div></div>
                      </div>
                      <div class="field"><div class="field-label">Пол</div>
                        <div class="field-value">${p.genderLabel || 'unknown'} ${p.gender ? '(' + p.gender + ')' : ''}</div>
                      </div>
                      <div class="field"><div class="field-label">Последний контакт</div>
                        <div class="field-value">${timeAgo}</div>
                      </div>
                    </div>
                    <div style="margin-top: 16px;">
                      <div class="card-header"><span class="card-title">Заметки</span></div>
                      ${p.notes && p.notes.length ? `
                        <div class="notes-list">
                          ${p.notes.map((n, i) => '<div class="note-item" style="display:flex;justify-content:space-between;align-items:center;">'
                            + '<span>• ' + n.replace(/</g,'<') + '</span>'
                            + '<button class="legend-item" style="font-size:11px;padding:2px 8px;" data-user-id="' + p.userId + '" data-note-idx="' + i + '">удалить</button>'
                            + '</div>').join('')}
                        </div>
                      ` : '<div class="muted">нет заметок</div>'}
                    </div>
                    <div style="margin-top: 12px; display: flex; gap: 8px;">
                      <button class="primary" id="rel-close-detail">Закрыть</button>
                    </div>
                  </div>
                `;
                detail.scrollIntoView({behavior: 'smooth'});
                $('#rel-close-detail').addEventListener('click', () => {
                  detail.style.display = 'none';
                  detail.innerHTML = '';
                });
                $$('.note-item button', detail).forEach(btn => {
                  btn.addEventListener('click', (e) => {
                    e.stopPropagation();
                    alert('Удаление заметок пока не реализовано в API');
                  });
                });
              }
              $('#rel-filter').addEventListener('input', () => renderRelationships(window.relationshipsData || []));
              $('#rel-refresh').addEventListener('click', loadRelationships);

              // ─── Page 4: Stats ───
              async function loadStats() {
                try {
                  const r = await fetch('/api/stats');
                  const data = await r.json();
                  renderStackedChart('replies-chart', 'replies-tooltip', 'replies-legend', data.dates, data.replies, REPLY_LEGEND);
                  renderStackedChart('tokens-chart', 'tokens-tooltip', 'tokens-legend', data.dates, data.tokens, TOKEN_LEGEND);
                } catch (e) { console.warn('Stats load failed', e); }
              }
              function renderStackedChart(svgId, tooltipId, legendId, dates, series, legend) {
                const svg = $('#' + svgId);
                const tooltip = $('#' + tooltipId);
                const legendContainer = $('#' + legendId);
                if (!dates.length || !series.length) return;
                const n = dates.length;
                const barW = 600 / n;
                const gap = barW * 0.3;
                const bw = barW - gap;
                let maxValTokens = Math.max(...series.map(s => s.reduce((a,b)=>a+b,0)), 1);
                const chartH = 260;
                const bottom = 30;
                let svgContent = '';
                for (let g = 0; g <= 5; g++) {
                  const y = bottom + (chartH * g / 5);
                  svgContent += `<line x1="0" y1="${y}" x2="1000" y2="${y}" class="chart-y-grid" />`;
                }
                series.forEach((stack, i) => {
                  let yBase = bottom + chartH;
                  stack.forEach((val, si) => {
                    const h = (val / maxVal) * chartH;
                    const y = yBase - h;
                    const x = 200 + i * (barW + gap) + si * 0;
                    svgContent += `
                      <g class="chart-bar" data-date="${dates[i]}" data-series="${legend[si].key}" data-value="${val}">
                        <rect x="${x}" y="${y}" width="${bw}" height="${h}" fill="${legend[si].color}" rx="3 3 0 0" />
                      </g>`;
                    yBase = y;
                  });
                });
                dates.forEach((d, i) => {
                  const x = 200 + i * (barW + gap) + bw / 2;
                  svgContent += `<text class="chart-x-label" x="${x}" y="${bottom + chartH + 20}">${d}</text>`;
                });
                svg.innerHTML = svgContent;
                $$('.chart-bar', svg).forEach(g => {
                  g.addEventListener('mousemove', e => {
                    const date = g.dataset.date;
                    const seriesKey = g.dataset.series;
                    const value = g.dataset.value;
                    const legendItem = legend.find(l => l.key === seriesKey);
                    tooltip.innerHTML = `
                      <div style="font-weight:700; margin-bottom:8px; color:${legendItem.color}">${date}</div>
                      <div class="chart-tooltip-row"><span class="chart-tooltip-label">${legendItem.label}</span><span class="chart-tooltip-value" style="color:${legendItem.color}">${Number(value).toLocaleString()}</span></div>
                    `;
                    tooltip.classList.add('visible');
                    const rect = svg.getBoundingClientRect();
                    tooltip.style.left = (e.clientX - rect.left + 16) + 'px';
                    tooltip.style.top = (e.clientY - rect.top - 80) + 'px';
                  });
                  g.addEventListener('mouseleave', () => tooltip.classList.remove('visible'));
                });
                legendContainer.innerHTML = legend.map(l => `
                  <div class="legend-item" data-series="${l.key}">
                    <span class="legend-dot" style="background:${l.color}"></span>${l.label}
                  </div>`).join('');
                $$('.legend-item', legendContainer).forEach(item => {
                  item.addEventListener('click', () => {
                    item.classList.toggle('inactive');
                    const key = item.dataset.series;
                    $$('.chart-bar', svg).forEach(bar => {
                      if (bar.dataset.series === key) bar.style.display = item.classList.contains('inactive') ? 'none' : '';
                    });
                  });
                });
              }

              // ─── Lazy panel loading ───
              const panelLoaders = {
                character: loadCharacter,
                control: loadControl,
                thoughts: loadThoughts,
                relationships: loadRelationships,
                stats: loadStats,
                logs: loadLogs
              };
              const loaded = new Set(['character']);
              $$('.tab').forEach(btn => {
                btn.addEventListener('click', () => {
                  const panel = btn.dataset.panel;
                  if (!loaded.has(panel) && panelLoaders[panel]) {
                    panelLoaders[panel]();
                    loaded.add(panel);
                  }
                });
              });
            </script>
            </body>
            </html>
            """;
}