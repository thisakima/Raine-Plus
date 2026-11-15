package ru.rainedev.raine.core;

import java.time.Duration;
import java.time.LocalTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.llm.LlmClient;
import ru.rainedev.raine.llm.Message;
import ru.rainedev.raine.memory.JournalWriter;

/**
 * Вечерняя рефлексия: раз в день, ближе к ночи, садится и пишет в дневник.
 * <p>
 * Не во сне, не перед сном ровно — а в окне 21:00–23:30, как живой человек,
 * который листает телефон в кровати и вдруг решает записать день. Время
 * каждый день разное, чтобы не выглядело как будильник.
 * <p>
 * Пишет, только если не спит и не в середине активного разговора. Если
 * не получилось сегодня — попробует завтра.
 */
public final class EveningReflection implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(EveningReflection.class);

    /** С какой минуты начинает пытаться. */
    private static final LocalTime WINDOW_START = LocalTime.of(21, 0);

    /** До какой минуты ещё не поздно. */
    private static final LocalTime WINDOW_END = LocalTime.of(23, 30);

    /** Проверяет «пора ли» каждые десять минут в окне. */
    private static final long CHECK_MINUTES = 10;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("raine-reflect").factory());

    private final JournalWriter writer;
    private final Supplier<String> systemPrompt;
    private final Supplier<java.util.List<Message>> contextSupplier;
    private final RandomGenerator random;

    private BooleanSupplier asleep = () -> false;
    private BooleanSupplier busy = () -> false;

    /** Писала ли уже сегодня — чтобы не дёргать лишний раз. */
    private java.time.LocalDate lastWrittenDay = java.time.LocalDate.of(1970, 1, 1);

    public EveningReflection(JournalWriter writer,
                             Supplier<String> systemPrompt,
                             Supplier<java.util.List<Message>> contextSupplier,
                             RandomGenerator random) {
        this.writer = writer;
        this.systemPrompt = systemPrompt;
        this.contextSupplier = contextSupplier;
        this.random = random;
    }

    public void asleepWhen(BooleanSupplier asleep) {
        this.asleep = asleep;
    }

    /** Пока она занята разговором, рефлексия ждёт — перебивать неловко. */
    public void busyWhen(BooleanSupplier busy) {
        this.busy = busy;
    }

    public void start() {
        scheduler.scheduleWithFixedDelay(this::tick,
                minutesUntilWindow(), CHECK_MINUTES, TimeUnit.MINUTES);
        log.info("Вечерняя рефлексия включена: окно {}–{}", WINDOW_START, WINDOW_END);
    }

    private long minutesUntilWindow() {
        LocalTime now = LocalTime.now();
        if (now.isBefore(WINDOW_START)) {
            return Duration.between(now, WINDOW_START).toMinutes() + 1;
        }
        if (now.isAfter(WINDOW_END)) {
            // окно прошло, ждём до завтра
            return Duration.between(now, WINDOW_START.plusHours(24)).toMinutes() + 1;
        }
        return CHECK_MINUTES;
    }

    private void tick() {
        try {
            LocalTime now = LocalTime.now();
            if (now.isBefore(WINDOW_START) || now.isAfter(WINDOW_END)) {
                return;
            }
            java.time.LocalDate today = java.time.LocalDate.now();
            if (!today.isAfter(lastWrittenDay)) {
                return;   // сегодня уже писала
            }
            if (asleep.getAsBoolean()) {
                return;
            }
            if (busy.getAsBoolean()) {
                return;
            }
            // случайность внутри окна: сегодня может не написать, если не «настроение»
            if (random.nextDouble() >= 0.85) {
                return;
            }
            log.info("Вечер: пора писать в дневник");
            writer.reflect(systemPrompt.get(), contextSupplier.get());
            lastWrittenDay = today;
        } catch (RuntimeException e) {
            log.warn("Рефлексия не удалась: {}", e.getMessage());
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
