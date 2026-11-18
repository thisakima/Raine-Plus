package ru.rainedev.raine.memory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Обида не исчезает ровно в тот час, когда закончилась холодность.
 * Живой человек остывает, но «ты вчера так и не извинился» ещё помнит —
 * и может сам вернуться к этому. Через пару дней отпускает.
 */
class RelationshipGrudgeTest {

    /** Часы, которые можно двигать вручную. */
    private static final class TestClock extends Clock {
        private long millis;

        TestClock(long millis) {
            this.millis = millis;
        }

        public void advance(long amount) {
            millis += amount;
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
    }

    @Test
    void whileColdSheKnowsWhatHurtHer(@TempDir Path dir) {
        TestClock clock = new TestClock(1_000_000);
        Relationships rel = new Relationships(dir.resolve("rel.json"), clock);
        rel.note(1L, "Миша", "шутил про мою семью и не остановился", "cold", "male");

        String ctx = rel.contextFor(1L);
        assertTrue(ctx.contains("currently upset"), ctx);
        assertTrue(ctx.contains("шутил про мою семью"), "причина обиды должна быть видна: " + ctx);
    }

    @Test
    void afterCoolingOffTheGrudgeStillSurfaces(@TempDir Path dir) {
        TestClock clock = new TestClock(1_000_000);
        Relationships rel = new Relationships(dir.resolve("rel.json"), clock);
        rel.note(1L, "Миша", "шутил про мою семью", "cold", "male");

        clock.advance(5 * 3600_000L);   // холодность (4ч) уже прошла
        String ctx = rel.contextFor(1L);
        assertFalse(ctx.contains("currently upset"), "уже не холодна: " + ctx);
        assertTrue(ctx.contains("cooled off"), "остыла, но помнит: " + ctx);
        assertTrue(ctx.contains("шутил про мою семью"), "причина всё ещё на языке: " + ctx);
    }

    @Test
    void afterAFewDaysSheLetsItGo(@TempDir Path dir) {
        TestClock clock = new TestClock(1_000_000);
        Relationships rel = new Relationships(dir.resolve("rel.json"), clock);
        rel.note(1L, "Миша", "шутил про мою семью", "cold", "male");

        clock.advance(5 * 3600_000L + 73 * 3600_000L);
        String ctx = rel.contextFor(1L);
        assertFalse(ctx.contains("cooled off"), "через пару дней отпускает: " + ctx);
        assertFalse(ctx.contains("still isn't fully settled"), ctx);
    }

    @Test
    void makingUpClosesTheGrudge(@TempDir Path dir) {
        TestClock clock = new TestClock(1_000_000);
        Relationships rel = new Relationships(dir.resolve("rel.json"), clock);
        rel.note(1L, "Миша", "шутил про мою семью", "cold", "male");
        clock.advance(5 * 3600_000L);

        rel.note(1L, "Миша", "извинился, поговорили нормально", "warm", "");
        String ctx = rel.contextFor(1L);
        assertFalse(ctx.contains("still isn't fully settled"),
                "помирились — старое не всплывает: " + ctx);
        assertFalse(ctx.contains("cooled off"), ctx);
    }

    @Test
    void grudgeSurvivesRestart(@TempDir Path dir) {
        TestClock clock = new TestClock(1_000_000);
        Path file = dir.resolve("rel.json");
        new Relationships(file, clock).note(1L, "Миша", "нагрубил мне", "cold", "male");

        clock.advance(5 * 3600_000L);
        Relationships reloaded = new Relationships(file, clock);
        assertTrue(reloaded.contextFor(1L).contains("нагрубил мне"),
                "причина обиды переживает перезапуск");
    }
}
