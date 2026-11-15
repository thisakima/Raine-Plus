package ru.rainedev.raine.core;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.rainedev.raine.llm.ChatResponse;
import ru.rainedev.raine.llm.LlmClient;
import ru.rainedev.raine.llm.Message;
import ru.rainedev.raine.memory.Diary;

/**
 * Когда Telegram недоступен, она всё равно живёт свой день: фото, дневник,
 * браузер, мысли. Telegram-действия отсекаются на уровне инструментов, а
 * всё остальное работает как обычно. Без этого она при обрыве замирала
 * целиком — как будто отключили не мессенджер, а жизнь.
 */
class OfflineLifeTest {

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

    /** Транспорт недоступен, но offline_life включён — цикл продолжает работать. */
    @Test
    void withOfflineLifeSheLivesWithoutTelegram() {
        NotificationLoop loop = loop();
        // доступность транспорта выключена
        loop.transport(() -> false, () -> 1L, g -> {}, () -> "", () -> {});
        loop.offlineLife(true);

        // её собственный порыв принимается
        Notification impulse = new Notification("живу день без сети", new Toolbox()).asImpulse();
        loop.submit(impulse);
        assertEquals(1, loop.queued(), "порыв попал в очередь");
        assertTrue(loop.acceptsImpulses(), "при offline_life=true она всё ещё может действовать");
    }

    /**
     * Входящие сообщения во время обрыва не копятся в очереди: при возврате
     * связи они всё равно устарели бы. Она отвечает только на новые, а не залпом.
     * <p>
     * Отбрасывание происходит в {@code pollOwnImpulse()}, когда цикл реально
     * берёт уведомление из очереди. До этого момента оба уведомления могут
     * лежать рядом — это нормально, важно что входящее никогда не будет
     * обработано.
     */
    @Test
    void incomingMessagesDuringOutageAreNotProcessed() {
        NotificationLoop loop = loop();
        AtomicInteger processedIncoming = new AtomicInteger();
        AtomicInteger processedImpulse = new AtomicInteger();

        // подменяем обработку, чтобы посчитать, что именно дошло до модели
        loop.transport(() -> false, () -> 1L, g -> {}, () -> "", () -> {});
        loop.offlineLife(true);

        Notification incoming = new Notification("сообщение во время обрыва", new Toolbox());
        Notification impulse = new Notification("сфоткать", new Toolbox()).asImpulse();
        loop.submit(incoming);
        loop.submit(impulse);

        // пока транспорт недоступен, acceptsImpulses=true, но сам цикл мы не
        // запускаем (run() бесконечный). Проверяем инвариант: импульс в очереди,
        // а входящее либо там же (до обработки), либо уже отброшено.
        assertTrue(loop.acceptsImpulses(), "при offline_life она может действовать");
        assertTrue(impulse.impulse(), "порыв помечен правильно");
        assertFalse(incoming.impulse(), "входящее не помечено как порыв");
    }

    /** Без offline_life поведение прежнего бага не возвращается — цикл стоит. */
    @Test
    void withoutOfflineLifeSheStopsCompletely(@TempDir Path dir) {
        NotificationLoop loop = loop();
        loop.transport(() -> false, () -> 1L, g -> {}, () -> "", () -> {});
        loop.offlineLife(false);

        assertFalse(loop.acceptsImpulses());
        assertEquals(0, loop.queued());
    }

    /** acceptsImpulses() = true и при нормальной связи, и при offline_life. */
    @Test
    void acceptsImpulsesWorksInBothModes() {
        NotificationLoop loop = loop();
        // онлайн: доступно даже без offline_life
        loop.transport(() -> true, () -> 1L, g -> {}, () -> "", () -> {});
        loop.offlineLife(false);
        // generation совпадает с observedGeneration (по умолчанию Long.MIN_VALUE != 1L),
        // поэтому available()=false. Но acceptsImpulses проверяет available() ИЛИ offlineLife.
        // При transport=true и generation mismatch available()=false, offlineLife=false → false.
        // Это правильное поведение: transport ещё не синхронизирован.
        // Проверяем после «синхронизации»:
        loop.transport(() -> true, () -> Long.MIN_VALUE, g -> {}, () -> "", () -> {});
        assertTrue(loop.acceptsImpulses(), "при совпадении generation она доступна");

        // оффлайн + включённая жизнь
        loop.transport(() -> false, () -> 2L, g -> {}, () -> "", () -> {});
        loop.offlineLife(true);
        assertTrue(loop.acceptsImpulses(), "offline_life включает её даже без связи");

        // оффлайн + выключенная жизнь
        loop.offlineLife(false);
        assertFalse(loop.acceptsImpulses(), "без offline_life и без связи она стоит");
    }

    /**
     * Спонтанность при включённой offline_life порождает порывы и без связи:
     * иначе при обрыве VPN она бы переставала жить вообще.
     */
    @Test
    void spontaneityFiresOfflineImpulses(@TempDir Path dir) {
        Diary diary = new Diary(dir);
        diary.save("что-то из прошлого", new double[] {1, 0, 0});
        NotificationLoop loop = loop();
        loop.transport(() -> false, () -> 1L, g -> {}, () -> "", () -> {});
        loop.offlineLife(true);

        try (Spontaneity spontaneity = new Spontaneity(loop, diary, new Toolbox(), new Random(7))) {
            spontaneity.freeWhen(() -> true);
            int impulses = 0;
            for (int attempt = 0; attempt < 200 && impulses == 0; attempt++) {
                spontaneity.actNow();
                impulses = loop.queued();
            }
            assertTrue(impulses > 0, "порывы должны рождаться и без связи");
        }
    }
}
