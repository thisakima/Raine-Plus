package ru.rainedev.raine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.rainedev.raine.llm.ChatResponse;
import ru.rainedev.raine.llm.LlmClient;
import ru.rainedev.raine.llm.Message;
import ru.rainedev.raine.memory.Diary;
import ru.rainedev.raine.core.Tool;

class SpontaneityTest {

    private static final LlmClient UNUSED = new LlmClient() {
            @Override public String modelName() { return "test-model"; }
        @Override
        public ChatResponse chat(String systemPrompt, List<Message> history, JsonNode tools) {
            throw new UnsupportedOperationException();
        }

        @Override
        public double[] embedding(String input) {
            return new double[] {1};
        }
    };

    private static NotificationLoop loop() {
        return new NotificationLoop(UNUSED, () -> "промпт", new Toolbox(), 40_000);
    }

    @Test
    void offlineSheCannotQueueImpulses(@TempDir Path dir) {
        NotificationLoop loop = loop();
        loop.transport(() -> false, () -> 1, ignored -> {}, () -> "", () -> {});
        try (Spontaneity spontaneity = new Spontaneity(loop, new Diary(dir), new Toolbox(), new Random(1))) {
            for (int i = 0; i < 100; i++) spontaneity.actNow();
            assertEquals(0, loop.queued());
            org.junit.jupiter.api.Assertions.assertFalse(spontaneity.isActing());
        }
    }

    @Test
    void onlyOneImpulseCanBeQueuedAndDiscardReleasesIt(@TempDir Path dir) {
        NotificationLoop loop = loop();
        try (Spontaneity spontaneity = new Spontaneity(loop, new Diary(dir), new Toolbox(), new Random(1))) {
            for (int i = 0; i < 100; i++) spontaneity.actNow();
            assertEquals(1, loop.queued());
            assertTrue(spontaneity.isActing());
            loop.discardMatching("quiet moment");
            assertEquals(0, loop.queued());
            org.junit.jupiter.api.Assertions.assertFalse(spontaneity.isActing());
            for (int i = 0; i < 100; i++) spontaneity.actNow();
            assertEquals(1, loop.queued());
        }
    }

    @Test
    void asleepSheHasNoImpulses(@TempDir Path dir) {
        // спящему человеку ничего не приходит в голову: ни разговоров,
        // ни записей в память, ни строчек в журнале
        Diary diary = new Diary(dir);
        diary.save("что-то из прошлого", new double[] {1, 0, 0});
        NotificationLoop loop = loop();

        try (Spontaneity spontaneity = new Spontaneity(loop, diary, new Toolbox(), new Random(1))) {
            spontaneity.asleepWhen(() -> true);
            spontaneity.actNow();

            assertEquals(0, loop.queued(), "во сне порывов быть не должно");
        }
    }

    @Test
    void awakeSheSometimesWritesFirst(@TempDir Path dir) {
        Diary diary = new Diary(dir);
        diary.save("что-то из прошлого", new double[] {1, 0, 0});
        NotificationLoop loop = loop();

        try (Spontaneity spontaneity = new Spontaneity(loop, diary, new Toolbox(), new Random(1))) {
            int impulses = 0;
            for (int attempt = 0; attempt < 40; attempt++) {
                spontaneity.actNow();
                impulses = loop.queued();
                if (impulses > 0) {
                    break;
                }
            }
            assertTrue(impulses > 0, "бодрствуя, она хоть иногда пишет сама");
        }
    }

    /** Пометка порыва «посидеть в сети» — по ней отличаю его от остальных. */
    private static final String BROWSING_MARK = "drift over to your browser";

    @Test
    void withABrowserSheSometimesGoesOnlineOnHerOwn(@TempDir Path dir) {
        Diary diary = new Diary(dir);
        diary.save("что-то из прошлого", new double[] {1, 0, 0});
        NotificationLoop loop = loop();

        try (Spontaneity spontaneity = new Spontaneity(loop, diary, new Toolbox(), new Random(7))) {
            spontaneity.browser(new Toolbox(
                    Tool.simple("browser_open", "открыть страницу", args -> "")));
            // свободное время подставляем: иначе тест зависел бы от часа суток
            spontaneity.freeWhen(() -> true);

            int browsing = 0;
            int other = 0;
            for (int attempt = 0; attempt < 600 && (browsing == 0 || other == 0); attempt++) {
                spontaneity.actNow();
                if (loop.queued() == 0) {
                    continue;
                }
                loop.discardMatching(BROWSING_MARK);
                if (loop.queued() == 0) {
                    browsing++;
                } else {
                    loop.discardMatching("quiet moment");
                    other++;
                }
            }
            assertTrue(browsing > 0, "иногда она сама идёт в сеть, а не только пишет людям");
            assertTrue(other > 0, "но не каждый порыв — в браузер");
        }
    }

    @Test
    void withoutABrowserThereIsNoBrowsingImpulse(@TempDir Path dir) {
        Diary diary = new Diary(dir);
        diary.save("что-то из прошлого", new double[] {1, 0, 0});
        NotificationLoop loop = loop();

        try (Spontaneity spontaneity = new Spontaneity(loop, diary, new Toolbox(), new Random(7))) {
            spontaneity.freeWhen(() -> true);
            for (int attempt = 0; attempt < 200; attempt++) {
                spontaneity.actNow();
                if (loop.queued() == 0) {
                    continue;
                }
                int before = loop.queued();
                loop.discardMatching(BROWSING_MARK);
                assertEquals(before, loop.queued(), "без браузера и порыва в сеть не бывает");
                loop.discardMatching("quiet moment");
            }
        }
    }

    /**
     * Главное про её свободу в сети: порыв задаёт обстановку, а не порядок
     * действий. Куда идти и что нажимать — решает она.
     */
    @Test
    void impulsesDescribeTheMomentNotTheSteps() {
        for (String prompt : List.of(Spontaneity.BROWSE_PROMPT, Spontaneity.TIKTOK_PROMPT)) {
            String lower = prompt.toLowerCase();
            assertFalse(lower.contains("start with #"), "ей диктуют первый шаг: " + prompt);
            assertFalse(lower.contains("then #"), "ей диктуют следующий шаг: " + prompt);
            assertFalse(lower.contains("scroll with #"), "ей диктуют, как листать: " + prompt);
            assertFalse(prompt.contains("\n  - "), "пошаговый список вернулся: " + prompt);
        }
        assertTrue(Spontaneity.BROWSE_PROMPT.contains("up to you"),
                "в порыве должно быть сказано, что решает она");
    }
}
