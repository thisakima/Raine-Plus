package ru.rainedev.raine.tiktok;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.rainedev.raine.config.Config;

/**
 * Пределы на действия в TikTok — то, что держит её похожей на человека и
 * бережёт аккаунт. Проверяются без браузера: это чистая логика счётчиков,
 * разогрева и охлаждения.
 */
class TikTokLimitsTest {

    /** Набор привычек с нужными потолками, охлаждением и разогревом. */
    private static Config.TikTok tiktok(int likeCap, int commentCap, int followCap, int dmCap,
                                        int cooldownMinutes, int warmupDays) {
        return new Config.TikTok(true, "yukiko", Path.of("data/tiktok"), 12,
                0.35, 0.06, 0.04, likeCap, commentCap, followCap, dmCap, cooldownMinutes, warmupDays);
    }

    @Test
    void warmupKeepsHerWatchingOnly(@TempDir Path dir) {
        // первые дни после входа она только смотрит: так аккаунт не палится сразу
        TikTokLimits limits = new TikTokLimits(tiktok(60, 8, 5, 10, 90, 3), dir.resolve("limits.properties"));
        assertTrue(limits.warmingUp(), "сразу после входа должен идти разогрев");
        assertTrue(limits.blockLike().isPresent(), "в разогреве лайкать нельзя");
        assertTrue(limits.blockComment().isPresent(), "в разогреве комментировать нельзя");
        assertTrue(limits.blockDm().isPresent(), "в разогреве писать в личку нельзя");
    }

    @Test
    void withoutWarmupSheCanAct(@TempDir Path dir) {
        TikTokLimits limits = new TikTokLimits(tiktok(60, 8, 5, 10, 0, 0), dir.resolve("limits.properties"));
        assertFalse(limits.warmingUp(), "без разогрева смотреть молча не нужно");
        assertTrue(limits.blockLike().isEmpty(), "пока потолок не достигнут, лайкать можно");
    }

    @Test
    void dailyCapsBlockFurtherActions(@TempDir Path dir) {
        TikTokLimits limits = new TikTokLimits(tiktok(2, 1, 5, 10, 0, 0), dir.resolve("limits.properties"));
        assertTrue(limits.blockLike().isEmpty());
        limits.noteLike();
        limits.noteLike();
        assertTrue(limits.blockLike().isPresent(), "после суточного потолка лайки блокируются");

        assertTrue(limits.blockComment().isEmpty());
        limits.noteComment();
        assertTrue(limits.blockComment().isPresent(), "после суточного потолка комментарии блокируются");
    }

    @Test
    void cooldownSpacesOutSessions(@TempDir Path dir) {
        TikTokLimits limits = new TikTokLimits(tiktok(60, 8, 5, 10, 90, 0), dir.resolve("limits.properties"));
        assertFalse(limits.coolingDown(), "до первой сессии охлаждения нет");
        limits.sessionEnded();
        assertTrue(limits.coolingDown(), "после сессии надо остыть — заходы редкие");
    }

    @Test
    void countersSurviveRestartWithinTheDay(@TempDir Path dir) {
        // счётчики живут в файле: иначе частые перезапуски превратили бы
        // «2 лайка в день» в «2 лайка за запуск»
        Path file = dir.resolve("limits.properties");
        TikTokLimits first = new TikTokLimits(tiktok(2, 8, 5, 10, 0, 0), file);
        first.noteLike();
        first.noteLike();

        TikTokLimits second = new TikTokLimits(tiktok(2, 8, 5, 10, 0, 0), file);
        assertTrue(second.blockLike().isPresent(), "после перезапуска суточный потолок всё ещё действует");
    }
}
