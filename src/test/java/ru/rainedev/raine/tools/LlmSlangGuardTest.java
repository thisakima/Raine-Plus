package ru.rainedev.raine.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Фильтр модельных шуток. В логах живой пример: «читер против читера это
 * назвагие фильма какое-то» — именно то, что запрещено в промпте, но модель
 * всё равно выдала. Значит нужен код, а не просьба словами.
 */
class LlmSlangGuardTest {

    @Test
    void catchesMovieTitleJoke() {
        assertTrue(LlmSlangGuard.looksGenerated("читер против читера это назвагие фильма какое-то"));
        assertTrue(LlmSlangGuard.looksGenerated("это название фильма какое-то"));
    }

    @Test
    void catchesSoundsLikeComparison() {
        assertTrue(LlmSlangGuard.looksGenerated("звучит как название группы из нулевых"));
        assertTrue(LlmSlangGuard.looksGenerated("звучит как сценарий дешёвого сериала"));
    }

    @Test
    void catchesLikeInAMovie() {
        assertTrue(LlmSlangGuard.looksGenerated("прям как в кино"));
        assertTrue(LlmSlangGuard.looksGenerated("как будто как в аниме"));
    }

    @Test
    void catchesReadyScreenplay() {
        assertTrue(LlmSlangGuard.looksGenerated("это уже готовый сценарий"));
        assertTrue(LlmSlangGuard.looksGenerated("это тянет на название книги"));
    }

    @Test
    void catchesDefinitionTic() {
        // определение через тире — занудный LLM-оборот, живая речь так не звучит
        assertTrue(LlmSlangGuard.looksGenerated("газ — это когда ты в зоне, а тиммейты в другом городе"));
        // но «это когда» без тире — обычная фраза, не трогаем
        assertFalse(LlmSlangGuard.looksGenerated("это когда ты приходишь и все молчат"));
    }

    @Test
    void letsNormalSpeechThrough() {
        assertFalse(LlmSlangGuard.looksGenerated("ахах плывут с ездой"));
        assertFalse(LlmSlangGuard.looksGenerated("ну такое себе"));
        assertFalse(LlmSlangGuard.looksGenerated("я вообще только проснулась, вставать не хочу"));
        assertFalse(LlmSlangGuard.looksGenerated("гость это когда приходят без приглашения"));
    }

    @Test
    void letsLegitimateMentionsOfFilmsThrough() {
        // она МОЖЕТ говорить о фильме — просто не сравнивать ситуацию с ним
        assertFalse(LlmSlangGuard.looksGenerated("вчера смотрела фильм, норм зашёл"));
        assertFalse(LlmSlangGuard.looksGenerated("посоветуй фильм какой-нибудь"));
        assertFalse(LlmSlangGuard.looksGenerated("обожаю этот сериал"));
    }

    @Test
    void emptyIsFine() {
        assertFalse(LlmSlangGuard.looksGenerated(""));
        assertFalse(LlmSlangGuard.looksGenerated(null));
    }

    @Test
    void throwsOnTell() {
        LlmSlangGuard guard = new LlmSlangGuard("переиграй");
        try {
            guard.check("это название фильма какое-то");
            org.junit.jupiter.api.Assertions.fail("должен был бросить LowQualityException");
        } catch (ru.rainedev.raine.core.LowQualityException expected) {
            assertTrue(expected.getMessage().contains("переиграй"));
        }
    }

    @Test
    void quietOnNormalText() {
        LlmSlangGuard guard = new LlmSlangGuard("переиграй");
        guard.check("норм, дома сижу");   // не должно бросить
    }
}
