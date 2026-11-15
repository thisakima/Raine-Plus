package ru.rainedev.raine.tools;

import ru.rainedev.raine.core.Surroundings;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.random.RandomGenerator;

/**
 * Пауза между сообщениями, соразмерная длине текста И контексту.
 * <p>
 * Скорость печати зависит от того, где она и что делает:
 * - На паре в колледже: медленно, с опечатками, короткие сообщения
 * - В транспорте: средне, с опечатками
 * - Дома вечером: быстро, длинные сообщения
 * - Ночь: очень медленно, много опечаток
 * <p>
 * Без неё четыре реплики приходят в одну секунду — сразу видно, что печатал
 * не человек. Если предыдущее сообщение было давно, ждать не нужно: пауза
 * изображает набор текста, а не задумчивость.
 */
public final class TypingRhythm {

    /**
     * Слов в минуту — привычная мера скорости печати. Слово считается за пять
     * знаков, то есть выходит около десяти знаков в секунду: бойко, но
     * по-человечески.
     */
    private static final int BASE_MIN_WORDS_PER_MINUTE = 120;
    private static final int BASE_MAX_WORDS_PER_MINUTE = 150;

    private static final int CHARS_PER_WORD = 5;

    /** Если с прошлого сообщения прошло больше — это новая мысль, а не продолжение. */
    private static final Duration CONVERSATION_GAP = Duration.ofSeconds(5);

    private static final Duration MAX_DELAY = Duration.ofSeconds(12);

    private final RandomGenerator random;
    private Instant previousMessageAt = Instant.EPOCH;

    /**
     * Контекст окружения для адаптации скорости печати.
     */
    private final Surroundings surroundings;

    /**
     * Множитель скорости: зависит от местоположения и активности.
     * - В колледже на паре: 0.3-0.5 (медленно, под партой)
     * - В транспорте: 0.6-0.8 (в транспорте)
     * - Дома вечером: 1.0-1.2 (быстро, комфортно)
     * - Ночь: 0.2-0.4 (очень медленно, полусонная)
     * - Утро: 0.5-0.7 (сонная)
     */
    private java.util.function.DoubleSupplier speedFactor;

    private void initSpeedFactor() {
        this.speedFactor = () -> {
            if (surroundings == null) {
                return 1.0;
            }
            Surroundings.LocationActivity loc = surroundings.currentLocationActivity();

            if (loc.isCollege()) {
                // В колледже: на паре медленно, на переменах быстрее
                LocalTime now = LocalTime.now();
                int hour = now.getHour();
                if (hour >= 8 && hour < 15) {
                    // На паре: очень медленно
                    return 0.2 + random.nextDouble() * 0.3; // 0.2-0.5
                }
                // Перемена / обед
                return 0.6 + random.nextDouble() * 0.3; // 0.6-0.9
            }

            if (loc.isTransit()) {
                return 0.5 + random.nextDouble() * 0.3; // 0.5-0.8
            }

            if (loc.isHome()) {
                LocalTime now = LocalTime.now();
                int hour = now.getHour();
                if (hour >= 22 || hour < 7) {
                    // Ночь / раннее утро
                    return 0.15 + random.nextDouble() * 0.25; // 0.15-0.4
                }
                if (hour >= 17 && hour < 22) {
                    // Вечер дома - быстро
                    return 1.0 + random.nextDouble() * 0.3; // 1.0-1.3
                }
                if (hour >= 11 && hour < 17) {
                    // День дома
                    return 0.8 + random.nextDouble() * 0.3; // 0.8-1.1
                }
            }

            if (loc.isNight()) {
                return 0.1 + random.nextDouble() * 0.2; // 0.1-0.3
            }

            return 1.0;
        };
    }

    public TypingRhythm(RandomGenerator random, Surroundings surroundings) {
        this.random = random;
        this.surroundings = surroundings;
        initSpeedFactor();
    }

    public TypingRhythm(RandomGenerator random) {
        this.random = random;
        this.surroundings = null;
        initSpeedFactor();
    }

    public void speedFactor(java.util.function.DoubleSupplier factor) {
        this.speedFactor = factor;
    }

    /** @return сколько ждать перед отправкой сообщения такой длины */
    public Duration delayFor(int messageLength, Instant now) {
        Duration sincePrevious = Duration.between(previousMessageAt, now);
        previousMessageAt = now;
        if (sincePrevious.compareTo(CONVERSATION_GAP) > 0) {
            return Duration.ZERO;
        }
        int wordsPerMinute = BASE_MIN_WORDS_PER_MINUTE
                + random.nextInt(BASE_MAX_WORDS_PER_MINUTE - BASE_MIN_WORDS_PER_MINUTE + 1);
        double factor = speedFactor.getAsDouble();
        if (factor > 0.2) {
            wordsPerMinute = (int) Math.round(wordsPerMinute * factor);
        }
        double charsPerMinute = (double) Math.max(20, wordsPerMinute) * CHARS_PER_WORD;
        Duration delay = Duration.ofMillis(Math.round((messageLength + 1) * 60_000.0 / charsPerMinute));
        return delay.compareTo(MAX_DELAY) > 0 ? MAX_DELAY : delay;
    }

    /**
     * @param keepTyping вызывается по ходу паузы: индикатор набора живёт около
     *                   пяти секунд, и без продления собеседник видит, что она
     *                   "передумала печатать"
     */
    public void sleepBefore(int messageLength, Runnable keepTyping) {
        Duration delay = delayFor(messageLength, Instant.now());
        if (delay.isZero()) {
            return;
        }
        try {
            long left = delay.toMillis();
            while (left > 0) {
                long step = Math.min(left, 4000);
                Thread.sleep(step);
                left -= step;
                if (left > 0) {
                    keepTyping.run();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}