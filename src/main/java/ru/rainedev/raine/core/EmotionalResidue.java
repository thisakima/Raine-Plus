package ru.rainedev.raine.core;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Эмоциональный след: то, как на неё повлиял разговор, живёт и затухает со
 * временем — и переносится в ДРУГИЕ чаты.
 * <p>
 * Зачем: живой человек не обнуляется между сообщениями. Поругался с одним —
 * ещё раздражён, когда пишет другой. Получил хорошие новости — весь день на
 * подъёме. Кто-то нагрубил — осадок остался на час. Без этого она реагирует
 * только на последнюю реплику и эмоционально «сбрасывается», как робот.
 * <p>
 * Механика: ОНА сама отмечает, что чувствует и почему ({@link #feel}). Код
 * ничего не придумывает — он хранит, ослабляет со временем (период полураспада
 * ~40 минут) и возвращает факт обратно в промпт. Как это отразить в словах —
 * решает модель; она может и «отпустить» чувство, если разговор важнее.
 * <p>
 * Хранится на диске, чтобы след пережил перезапуск: настроение не должно
 * обнуляться оттого, что бота перезапустили.
 */
public final class EmotionalResidue {

    private static final Logger log = LoggerFactory.getLogger(EmotionalResidue.class);

    /** Период полураспада чувства в миллисекундах (~40 минут). */
    private static final long HALF_LIFE_MS = 40 * 60_000L;

    /** Ниже этой интенсивности след считается рассеявшимся. */
    private static final double GONE = 0.06;

    /** Сколько недавних чувств помнить (свежее + пара предыдущих). */
    private static final int KEEP = 4;

    /** Сколько минут след «переносится» в другой чат как активный. */
    private static final long FRESH_MINUTES = 90;

    private final java.nio.file.Path file;
    private final ReentrantLock lock = new ReentrantLock();
    private final Deque<Feeling> feelings = new ArrayDeque<>();

    /**
     * Слушатель, который получает уведомление о каждом новом чувстве. Нужен,
     * чтобы Mood мог сдвигать энергию от реальных событий, а не только от
     * времени суток.
     */
    private java.util.function.Consumer<Feeling> onNewFeeling = f -> {};

    public void onNewFeeling(java.util.function.Consumer<Feeling> listener) {
        this.onNewFeeling = listener == null ? f -> {} : listener;
    }

    /**
     * Одно чувство: что она испытывает, насколько сильно, почему и когда.
     * Метка — короткое слово («раздражение», «радость», «обида»), why — контекст
     * в её словах, intensity 0..1, atMillis — момент, когда отметила.
     */
    public record Feeling(String label, String why, double intensity, long atMillis) {
        /** Интенсивность с учётом затухания по времени. */
        double decayedNow() {
            long age = Math.max(0, System.currentTimeMillis() - atMillis);
            double factor = Math.pow(0.5, (double) age / HALF_LIFE_MS);
            return intensity * factor;
        }

        long ageMinutes() {
            return Math.max(0, (System.currentTimeMillis() - atMillis) / 60_000);
        }
    }

    public EmotionalResidue(java.nio.file.Path file) {
        this.file = file;
        load();
    }

    /**
     * Она отмечает, как на неё повлиял разговор. Свежее сильное чувство
     * вытесняет предыдущее в историю; слабое — просто добавляется.
     *
     * @param label     короткое название чувства
     * @param why       почему (в её словах, коротко)
     * @param intensity 0..1 — насколько сильно
     */
    public void feel(String label, String why, double intensity) {
        String l = label == null ? "" : label.strip();
        if (l.isEmpty()) return;
        double i = Math.clamp(intensity, 0, 1);
        String w = why == null ? "" : why.strip();
        lock.lock();
        try {
            feelings.addFirst(new Feeling(l, w, i, System.currentTimeMillis()));
            while (feelings.size() > KEEP) feelings.removeLast();
            save();
            Feeling just = feelings.peekFirst();
            if (just != null) onNewFeeling.accept(just);
        } finally {
            lock.unlock();
        }
        log.info("Эмоциональный след: {} ({}), интенсивность {}", l, w, String.format("%.2f", i));
    }

    /**
     * Текущий живой след: самое сильное из ещё не рассеявшихся чувств.
     * Пусто, если всё отпустило.
     */
    public Optional<Feeling> dominant() {
        lock.lock();
        try {
            Feeling best = null;
            for (Feeling f : feelings) {
                if (f.decayedNow() < GONE) continue;
                if (best == null || f.decayedNow() > best.decayedNow()) best = f;
            }
            return Optional.ofNullable(best);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Дополнение к системному промпту. Возвращает факт о её эмоциональном
     * состоянии, а не команду: как это выразить (или отпустить) — решает она.
     * Важно: след виден во ВСЕХ чатах, поэтому он и переносится между ними.
     */
    public String asPromptSuffix() {
        Optional<Feeling> dom = dominant();
        if (dom.isEmpty()) return "";
        Feeling f = dom.get();
        double now = f.decayedNow();
        String strength = now >= 0.66 ? "strong" : now >= 0.33 ? "noticeable" : "fading";
        String when = f.ageMinutes() <= 0 ? "just now"
                : f.ageMinutes() < 60 ? f.ageMinutes() + " min ago"
                : (f.ageMinutes() / 60) + " h ago";

        StringBuilder out = new StringBuilder("<emotional_residue>\n");
        out.append("Right now you still feel: ").append(f.label())
                .append(" (").append(strength).append(", from ").append(when).append(").");
        if (!f.why().isEmpty()) {
            out.append(" It came from: ").append(f.why()).append('.');
        }
        out.append('\n');
        out.append("This feeling carries over - it's still with you even if you're now ")
                .append("talking to someone else or about something else. It may color your ")
                .append("tone, patience and how much you feel like talking. This is a fact ")
                .append("about you, not a command: you can let it show, or you can set it ")
                .append("aside if this moment calls for it. A person doesn't reset between ")
                .append("chats - neither do you.\n");

        // пара предыдущих чувств — лёгкий шлейф, чтобы было видно движение настроения
        List<Feeling> past = recentExcluding(f);
        if (!past.isEmpty()) {
            out.append("Earlier you also felt: ");
            for (int i = 0; i < past.size(); i++) {
                if (i > 0) out.append(", ");
                Feeling p = past.get(i);
                out.append(p.label()).append(" (").append(p.ageMinutes()).append(" min ago, now ")
                        .append(p.decayedNow() >= 0.33 ? "fading" : "mostly gone").append(')');
            }
            out.append(".\n");
        }
        out.append("</emotional_residue>\n");
        return out.toString();
    }

    private List<Feeling> recentExcluding(Feeling skip) {
        lock.lock();
        try {
            return feelings.stream()
                    .filter(f -> f != skip && f.decayedNow() >= GONE && f.ageMinutes() <= FRESH_MINUTES)
                    .limit(2)
                    .toList();
        } finally {
            lock.unlock();
        }
    }

    /** Короткая метка текущего состояния для веб-панели. Пусто, если следа нет. */
    public String currentLabel() {
        return dominant().map(Feeling::label).orElse("");
    }

    /** Было ли что-то сильное недавно — полезно, чтобы решать, стоит ли лезть в чаты. */
    public boolean unsettled() {
        return dominant().map(f -> f.decayedNow() >= 0.33).orElse(false);
    }

    private void load() {
        lock.lock();
        try {
            if (!java.nio.file.Files.exists(file)) return;
            for (String line : java.nio.file.Files.readAllLines(file)) {
                Feeling f = parse(line);
                if (f != null && f.decayedNow() >= GONE) feelings.addLast(f);
            }
            while (feelings.size() > KEEP) feelings.removeFirst();
        } catch (Exception e) {
            log.debug("Не удалось прочитать эмоциональный след: {}", e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    private void save() {
        try {
            java.nio.file.Path dir = file.getParent();
            if (dir != null) java.nio.file.Files.createDirectories(dir);
            StringBuilder sb = new StringBuilder();
            for (Feeling f : feelings) sb.append(serialize(f)).append('\n');
            java.nio.file.Files.writeString(file, sb.toString());
        } catch (Exception e) {
            log.debug("Не удалось сохранить эмоциональный след: {}", e.getMessage());
        }
    }

    /** Формат: label\tintensity\tatMillis\twhy — why последним, т.к. может содержать табы. */
    private static String serialize(Feeling f) {
        String why = f.why() == null ? "" : f.why().replace('\t', ' ').replace('\n', ' ');
        return f.label().replace('\t', ' ') + "\t" + f.intensity() + "\t" + f.atMillis() + "\t" + why;
    }

    private static Feeling parse(String line) {
        if (line == null || line.isBlank()) return null;
        String[] parts = line.split("\t", 4);
        if (parts.length < 3) return null;
        try {
            double intensity = Double.parseDouble(parts[1]);
            long at = Long.parseLong(parts[2]);
            if (at <= 0 || at > Instant.now().toEpochMilli() + 60_000) return null;
            String why = parts.length >= 4 ? parts[3] : "";
            return new Feeling(parts[0], why, Math.clamp(intensity, 0, 1), at);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
