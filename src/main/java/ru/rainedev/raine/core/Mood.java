package ru.rainedev.raine.core;

import java.time.LocalTime;
import java.util.concurrent.atomic.AtomicReference;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Состояние: энергия и настроение. Детерминированная база от времени суток
 * плюс дрейф и реакции на события.
 * <p>
 * Зачем: живой человек к вечеру устаёт, после обеда вялый, утром сонный.
 * Без этого она в 3 ночи пишет бодро и многословно, а в 8 утра — как
 * ни в чём не бывало. Состояние влияет на то, как ОНА сама решает писать:
 * код только сообщает факты, слова и длину выбирает модель.
 * <p>
 * Настроение не навязывается жёстко: это подсказка, а не команда. Модель
 * может решить иначе, если контекст разговора того требует.
 */
public final class Mood {

    private static final Logger log = LoggerFactory.getLogger(Mood.class);

    /** Базовая энергия по часу суток (0..1). */
    private static double baseEnergy(int hour) {
        if (hour >= 2 && hour < 6) return 0.10;    // глубокая ночь, должна спать
        if (hour < 8) return 0.30;                 // просыпается
        if (hour < 11) return 0.65;                // утро, ещё бодрая
        if (hour < 14) return 0.70;                // день, пик
        if (hour < 16) return 0.55;                // послеобеденный спад
        if (hour < 19) return 0.60;                // вечерняя активность
        if (hour < 22) return 0.45;                // устаёт
        if (hour < 24) return 0.30;                // сонная
        return 0.20;                               // 0-2 часа
    }

    private final RandomGenerator random;

    /** Небольшой дрейф энергии, чтобы не было одинаково каждый день. */
    private volatile double drift = 0;

    /** Последнее изменение настроения и его значение. */
    private final AtomicReference<Shift> shift = new AtomicReference<>(new Shift("", System.currentTimeMillis()));

    private record Shift(String label, long atMillis) {}

    public Mood(RandomGenerator random) {
        this.random = random;
    }

    /** Текущая энергия 0..1 с учётом времени и дрейфа. */
    public double energy() {
        double base = baseEnergy(LocalTime.now().getHour());
        // дрейф медленно возвращается к нулю
        drift *= 0.98;
        return Math.clamp(base + drift, 0.05, 1.0);
    }

    /**
     * Событие, которое сдвигает настроение. Метка — короткое описание
     * для модели, она сама решит, как это отразить в тексте.
     */
    public void event(String label, double energyDelta) {
        drift = Math.clamp(drift + energyDelta, -0.3, 0.3);
        shift.set(new Shift(label, System.currentTimeMillis()));
    }

    /** Сообщение владельца слегка бодрит. */
    public void ownerWrote() {
        event("тебе написал владелец", 0.05);
    }

    /** Долгая активность утомляет. */
    public void longConversation() {
        event("ты долго переписываешься", -0.03);
    }

    /**
     * Подсказка для промпта. Код описывает СОСТОЯНИЕ фактами, а не
     * предписывает конкретные слова — модель сама решает, как это
     * выразить (или вообще проигнорировать, если разговор важнее).
     */
    public String asPromptSuffix() {
        double e = energy();
        String level;
        if (e < 0.2) level = "very low";
        else if (e < 0.4) level = "low";
        else if (e < 0.6) level = "medium-low";
        else if (e < 0.8) level = "medium";
        else level = "high";

        StringBuilder out = new StringBuilder("<your_state>\n");
        out.append("Energy: ").append(level).append(". ");
        out.append("This is a fact about you right now, not a command. ");
        out.append("Let it influence HOW you write (length, typos, patience) ");
        out.append("but decide the words yourself. Low energy doesn't mean ");
        out.append("you must be rude or silent - it just means you're not ");
        out.append("bubbly.\n");

        Shift s = shift.get();
        long minutesAgo = (System.currentTimeMillis() - s.atMillis()) / 60_000;
        if (!s.label().isEmpty() && minutesAgo < 60) {
            out.append("Recent: ").append(s.label())
                    .append(" (").append(minutesAgo).append(" min ago). ");
            out.append("It may still affect your mood, or you may have moved on. ");
            out.append("Your call.\n");
        }
        out.append("</your_state>\n");
        return out.toString();
    }

    /**
     * Множитель скорости печати: уставшая печатает медленнее, бодрая — быстрее.
     * Живой человек не печатает всегда с одной скоростью.
     */
    public double typingSpeedFactor() {
        double e = energy();
        // от 0.7 (сонная) до 1.25 (бодрая)
        return 0.7 + (e - 0.1) * 0.6;
    }
}
