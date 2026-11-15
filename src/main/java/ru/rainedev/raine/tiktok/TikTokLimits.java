package ru.rainedev.raine.tiktok;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.config.Config;

/**
 * Пределы на действия в TikTok — то, что держит её похожей на человека и
 * бережёт аккаунт.
 * <p>
 * Три барьера: суточные потолки (лайков, комментариев, репостов, подписок,
 * личных сообщений), разогрев (первые дни только смотрит) и охлаждение между
 * сессиями. Счётчики живут в файле, поэтому перезапуск не обнуляет сутки —
 * иначе частые перезапуски превратили бы «60 лайков в день» в «60 за запуск».
 * <p>
 * Автоматизация TikTok нарушает его правила и может привести к бану, поэтому
 * потолки намеренно низкие, а разогрев — обязателен.
 */
public final class TikTokLimits {

    private static final Logger log = LoggerFactory.getLogger(TikTokLimits.class);

    private final Config.TikTok config;
    private final Path file;

    private LocalDate day = LocalDate.now();
    private int likes;
    private int comments;
    private int reposts;
    private int follows;
    private int dms;
    private Instant lastSessionAt;
    private LocalDate firstUseDay;

    public TikTokLimits(Config.TikTok config, Path file) {
        this.config = config;
        this.file = file;
        load();
    }

    /** Отмечает первый заход — с этого дня идёт отсчёт разогрева. */
    public synchronized void touchFirstUse() {
        if (firstUseDay == null) {
            firstUseDay = LocalDate.now();
            save();
        }
    }

    /** Первые дни после входа она только смотрит: так аккаунт не палится сразу. */
    public synchronized boolean warmingUp() {
        if (firstUseDay == null) {
            return config.warmupDays() > 0;
        }
        long days = java.time.temporal.ChronoUnit.DAYS.between(firstUseDay, LocalDate.now());
        return days < config.warmupDays();
    }

    /** Сколько дней осталось смотреть молча. */
    public synchronized long warmupDaysLeft() {
        if (config.warmupDays() <= 0) {
            return 0;
        }
        LocalDate from = firstUseDay == null ? LocalDate.now() : firstUseDay;
        long days = java.time.temporal.ChronoUnit.DAYS.between(from, LocalDate.now());
        return Math.max(0, config.warmupDays() - days);
    }

    /** Прошло ли достаточно времени с прошлой сессии. */
    public synchronized boolean coolingDown() {
        if (config.cooldownMinutes() <= 0 || lastSessionAt == null) {
            return false;
        }
        return minutesSinceSession() < config.cooldownMinutes();
    }

    public synchronized long cooldownMinutesLeft() {
        if (lastSessionAt == null) {
            return 0;
        }
        return Math.max(0, config.cooldownMinutes() - minutesSinceSession());
    }

    private long minutesSinceSession() {
        return Duration.between(lastSessionAt, Instant.now()).toMinutes();
    }

    public synchronized void sessionEnded() {
        lastSessionAt = Instant.now();
        save();
    }

    /** Почему нельзя лайкнуть — или пусто, если можно. */
    public synchronized Optional<String> blockLike() {
        if (warmingUp()) {
            return Optional.of(warmupReason());
        }
        if (likes >= config.dailyLikeCap()) {
            return Optional.of("You've already liked " + likes + " videos today — that's your limit, "
                    + "let the account rest until tomorrow.");
        }
        return Optional.empty();
    }

    public synchronized Optional<String> blockComment() {
        if (warmingUp()) {
            return Optional.of(warmupReason());
        }
        if (comments >= config.dailyCommentCap()) {
            return Optional.of("You've commented " + comments + " times today — that's plenty, "
                    + "more would look like a bot.");
        }
        return Optional.empty();
    }

    public synchronized Optional<String> blockRepost() {
        if (warmingUp()) {
            return Optional.of(warmupReason());
        }
        if (reposts >= config.dailyLikeCap()) {
            return Optional.of("You've reposted a lot today — leave it.");
        }
        return Optional.empty();
    }

    public synchronized Optional<String> blockFollow() {
        if (warmingUp()) {
            return Optional.of(warmupReason());
        }
        if (follows >= config.dailyFollowCap()) {
            return Optional.of("You've followed " + follows + " people today — that's your limit.");
        }
        return Optional.empty();
    }

    public synchronized Optional<String> blockDm() {
        if (warmingUp()) {
            return Optional.of(warmupReason());
        }
        if (dms >= config.dailyDmCap()) {
            return Optional.of("You've sent " + dms + " direct messages today — that's your limit.");
        }
        return Optional.empty();
    }

    private String warmupReason() {
        long left = warmupDaysLeft();
        return "You're still in your first days on TikTok (about " + left
                + " more day(s) of just watching). Lurk for now — no likes, comments or messages yet, "
                + "so the account looks natural.";
    }

    public synchronized void noteLike() {
        likes++;
        save();
    }

    public synchronized void noteComment() {
        comments++;
        save();
    }

    public synchronized void noteRepost() {
        reposts++;
        save();
    }

    public synchronized void noteFollow() {
        follows++;
        save();
    }

    public synchronized void noteDm() {
        dms++;
        save();
    }

    /** Как потратила сегодняшний лимит — для неё самой и для промпта. */
    public synchronized String usage() {
        StringBuilder out = new StringBuilder();
        out.append("TikTok today: ").append(likes).append('/').append(config.dailyLikeCap()).append(" likes, ")
                .append(comments).append('/').append(config.dailyCommentCap()).append(" comments, ")
                .append(reposts).append(" reposts, ")
                .append(follows).append('/').append(config.dailyFollowCap()).append(" follows, ")
                .append(dms).append('/').append(config.dailyDmCap()).append(" DMs.");
        if (warmingUp()) {
            out.append(" Still warming up (").append(warmupDaysLeft()).append(" day(s) watch-only).");
        }
        if (coolingDown()) {
            out.append(" Cooling down (").append(cooldownMinutesLeft()).append(" min left).");
        }
        return out.toString();
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            log.warn("Не удалось прочитать пределы TikTok {}: {}", file, e.getMessage());
            return;
        }
        LocalDate savedDay = parseDate(props.getProperty("day"));
        this.firstUseDay = parseDate(props.getProperty("firstUseDay"));
        this.lastSessionAt = parseInstant(props.getProperty("lastSessionAt"));
        if (savedDay != null && savedDay.equals(LocalDate.now())) {
            this.day = savedDay;
            this.likes = parseInt(props.getProperty("likes"));
            this.comments = parseInt(props.getProperty("comments"));
            this.reposts = parseInt(props.getProperty("reposts"));
            this.follows = parseInt(props.getProperty("follows"));
            this.dms = parseInt(props.getProperty("dms"));
        } else {
            // новые сутки — счётчики с нуля
            this.day = LocalDate.now();
        }
    }

    private void save() {
        // сутки могли смениться с прошлой записи — обнуляем счётчики
        if (!day.equals(LocalDate.now())) {
            day = LocalDate.now();
            likes = comments = reposts = follows = dms = 0;
        }
        Properties props = new Properties();
        props.setProperty("day", day.toString());
        props.setProperty("likes", Integer.toString(likes));
        props.setProperty("comments", Integer.toString(comments));
        props.setProperty("reposts", Integer.toString(reposts));
        props.setProperty("follows", Integer.toString(follows));
        props.setProperty("dms", Integer.toString(dms));
        if (firstUseDay != null) {
            props.setProperty("firstUseDay", firstUseDay.toString());
        }
        if (lastSessionAt != null) {
            props.setProperty("lastSessionAt", Long.toString(lastSessionAt.getEpochSecond()));
        }
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "TikTok daily limits — resets each day");
            }
        } catch (IOException e) {
            log.warn("Не удалось сохранить пределы TikTok {}: {}", file, e.getMessage());
        }
    }

    private static LocalDate parseDate(String value) {
        try {
            return value == null || value.isBlank() ? null : LocalDate.parse(value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Instant parseInstant(String value) {
        try {
            return value == null || value.isBlank() ? null : Instant.ofEpochSecond(Long.parseLong(value));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int parseInt(String value) {
        try {
            return value == null ? 0 : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
