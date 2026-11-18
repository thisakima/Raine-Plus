package ru.rainedev.raine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Эмоциональный след: чувство живёт, затухает и переживает перезапуск.
 * Без этого она эмоционально обнуляется между чатами — как робот, а не
 * как человек, который ещё помнит, как его задел последний разговор.
 */
class EmotionalResidueTest {

    @Test
    void freshFeelingShowsUpInPrompt(@TempDir Path dir) {
        EmotionalResidue residue = new EmotionalResidue(dir.resolve("feel.txt"));
        residue.feel("раздражение", "грубили в группе", 0.8);

        Optional<EmotionalResidue.Feeling> dom = residue.dominant();
        assertTrue(dom.isPresent(), "свежее чувство видно");
        assertEquals("раздражение", dom.get().label());
        assertTrue(dom.get().intensity() > 0.7);

        String prompt = residue.asPromptSuffix();
        assertTrue(prompt.contains("emotional_residue"), prompt);
        assertTrue(prompt.contains("раздражение"), prompt);
        assertTrue(prompt.contains("грубили в группе"), prompt);
        assertTrue(prompt.contains("carries over"), "должно быть сказано, что след переносится");
    }

    @Test
    void emptyWhenNothingFelt(@TempDir Path dir) {
        EmotionalResidue residue = new EmotionalResidue(dir.resolve("feel.txt"));
        assertTrue(residue.dominant().isEmpty());
        assertEquals("", residue.asPromptSuffix());
    }

    @Test
    void survivesRestart(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("feel.txt");
        EmotionalResidue first = new EmotionalResidue(file);
        first.feel("радость", "получила хорошую новость", 0.9);

        // перезапуск — новый экземпляр на том же файле
        EmotionalResidue second = new EmotionalResidue(file);
        assertTrue(second.dominant().isPresent(), "после перезапуска чувство не теряется");
        assertEquals("радость", second.dominant().get().label());
    }

    @Test
    void oldFeelingIsDecayedAndGone(@TempDir Path dir) throws Exception {
        // пишем руками старое чувство (5 часов назад) — должно быть ниже порога
        Path file = dir.resolve("feel.txt");
        long fiveHoursAgo = System.currentTimeMillis() - 5 * 60 * 60_000L;
        Files.writeString(file, "злость\t0.9\t" + fiveHoursAgo + "\tкто-то нагрубил\n");

        EmotionalResidue residue = new EmotionalResidue(file);
        assertFalse(residue.dominant().isPresent(),
                "за 5 часов с периодом полураспада 40 мин след уже рассеялся");
    }

    @Test
    void strongestRecentWins(@TempDir Path dir) {
        EmotionalResidue residue = new EmotionalResidue(dir.resolve("feel.txt"));
        residue.feel("скука", "листала ленту", 0.3);
        residue.feel("радость", "встретила подругу", 0.9);
        residue.feel("раздражение", "задержали автобус", 0.5);

        assertEquals("радость", residue.dominant().get().label(),
                "доминирует самое сильное свежее чувство, а не последнее");
    }

    @Test
    void listenerCalledOnNewFeeling(@TempDir Path dir) {
        EmotionalResidue residue = new EmotionalResidue(dir.resolve("feel.txt"));
        AtomicReference<EmotionalResidue.Feeling> captured = new AtomicReference<>();
        residue.onNewFeeling(captured::set);
        residue.feel("нежность", "написал владелец", 0.7);

        assertEquals("нежность", captured.get().label());
        assertTrue(captured.get().intensity() > 0.6);
    }

    @Test
    void ignoresEmptyLabel(@TempDir Path dir) {
        EmotionalResidue residue = new EmotionalResidue(dir.resolve("feel.txt"));
        residue.feel("", "причина", 0.5);
        residue.feel("   ", "причина", 0.5);
        assertTrue(residue.dominant().isEmpty(), "пустая метка не сохраняется");
    }
}
