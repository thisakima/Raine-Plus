package ru.rainedev.raine.core;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * Ночь. Человек не сидит в переписке круглосуточно: он ложится ближе к полуночи
 * и встаёт утром, а сообщения, пришедшие ночью, читает после пробуждения.
 * <p>
 * Время отхода ко сну и подъёма выбирается на каждую ночь заново в пределах
 * окна: ровно в 23:00 каждый день ложится расписание, а не живой человек.
 */
public final class NightSleep {

    /** Между этими часами ложится. Позже полуночи — это уже следующие сутки. */
    private final LocalTime bedtimeFrom;
    private final LocalTime bedtimeTo;

    /** Между этими часами встаёт. */
    private final LocalTime wakeFrom;
    private final LocalTime wakeTo;

    private final Clock clock;
    private final RandomGenerator random;

    /** Расписание держится на сутки, иначе оно менялось бы при каждой проверке. */
    private LocalDate plannedFor;
    private LocalDateTime bedtime;
    private LocalDateTime wakeTime;

    public NightSleep(LocalTime bedtimeFrom, LocalTime bedtimeTo, LocalTime wakeFrom, LocalTime wakeTo,
                      Clock clock, RandomGenerator random) {
        this.bedtimeFrom = bedtimeFrom;
        this.bedtimeTo = bedtimeTo;
        this.wakeFrom = wakeFrom;
        this.wakeTo = wakeTo;
        this.clock = clock;
        this.random = random;
    }

    /** @return сколько осталось спать, если сейчас ночь */
    public Optional<Duration> untilMorning() {
        LocalDateTime now = LocalDateTime.now(clock);
        plan(now);

        // мы внутри окна сна только если now >= bedtime и now < wakeTime
        if (now.isBefore(bedtime) || !now.isBefore(wakeTime)) {
            return Optional.empty();
        }
        return Optional.of(Duration.between(now, wakeTime));
    }

    public LocalDateTime bedtime() {
        plan(LocalDateTime.now(clock));
        return bedtime;
    }

    public LocalDateTime wakeTime() {
        plan(LocalDateTime.now(clock));
        return wakeTime;
    }

    /**
     * Вычисляет ближайшую пару (bedtime, wakeTime).
     * <p>
     * Окно отхода ко сну может пересекать полночь (23:00–01:00), а может
     * целиком лежать после полуночи (00:00–02:00). Подъём всегда на
     * следующий день после отхода. Если сейчас момент после того, как
     * она уже должна была встать по ближайшему расписанию, значит эта
     * ночь прошла и нужно планировать следующую — иначе untilMorning
     * вернёт сотни минут «сна» посреди дня.
     */
    private void plan(LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        if (today.equals(plannedFor)) {
            return;
        }
        plannedFor = today;

        // если сейчас до утреннего окна (например, 3 часа ночи), то идёт
        // ночь, начавшаяся вчера вечером — она ещё не закончилась.
        // иначе в час ночи расписание перевыбиралось бы посреди сна.
        boolean earlyMorning = now.toLocalTime().isBefore(wakeTo);

        LocalDateTime candidateBed = bedFor(earlyMorning ? today.minusDays(1) : today);
        LocalDateTime candidateWake = wakeAfter(candidateBed);

        // если эта ночь уже полностью прошла (now >= wake), берём следующую
        if (!now.isBefore(candidateWake)) {
            candidateBed = bedFor(candidateBed.toLocalDate().plusDays(1));
            candidateWake = wakeAfter(candidateBed);
        }

        bedtime = candidateBed;
        wakeTime = candidateWake;
    }

    /** Время отхода ко сну для ночи, приходящейся на указанную дату. */
    private LocalDateTime bedFor(LocalDate night) {
        LocalDateTime bed = LocalDateTime.of(night, pick(bedtimeFrom, bedtimeTo));
        // окно отхода может пересекать полночь: если pick попал за полночь,
        // а начало окна было накануне, сдвигаем на следующий день
        if (bedtimeTo.isBefore(bedtimeFrom) && bed.toLocalTime().isBefore(bedtimeFrom)) {
            bed = bed.plusDays(1);
        }
        return bed;
    }

    /** Время подъёма всегда идёт после отхода ко сну. */
    private LocalDateTime wakeAfter(LocalDateTime bed) {
        LocalDateTime wake = LocalDateTime.of(bed.toLocalDate(), pick(wakeFrom, wakeTo));
        if (!wake.isAfter(bed)) {
            wake = wake.plusDays(1);
        }
        return wake;
    }

    private LocalTime pick(LocalTime from, LocalTime to) {
        int start = from.toSecondOfDay();
        int end = to.toSecondOfDay();
        int span = end >= start ? end - start : (86400 - start) + end;
        return LocalTime.ofSecondOfDay((start + random.nextInt(Math.max(1, span))) % 86400);
    }
}
