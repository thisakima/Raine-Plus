package ru.rainedev.raine.memory;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReplyRemindersTest {
    @TempDir Path dir;
    private final Clock clock = Clock.fixed(Instant.ofEpochMilli(100_000), ZoneOffset.UTC);

    @Test void genericTimeWordsDoNotCreatePromises() {
        DeferredReplies replies = new DeferredReplies(dir.resolve("deferred"), clock);
        for (String text : new String[] {"щас ем", "потом пошла домой", "я на паре", "занята", "скоро каникулы",
                "позже не напишу", "сейчас не отвечу"}) {
            replies.checkLastSent(1, "friend", text);
            assertTrue(replies.due(0).isEmpty(), text);
        }
    }

    @Test void explicitPromiseIsOneShotAndContinuationClosesIt() {
        Path file = dir.resolve("deferred");
        DeferredReplies replies = new DeferredReplies(file, clock);
        replies.checkLastSent(1, "friend", "на паре, потом напишу");
        assertTrue(replies.due(1).isEmpty());
        assertEquals(1, replies.due(0).size());
        assertTrue(replies.due(0).isEmpty());
        replies = new DeferredReplies(file, clock);
        assertTrue(replies.due(0).isEmpty(), "Reminder must stay consumed across restart");
        replies.checkLastSent(1, "friend", "вот что я хотела рассказать");
        replies.checkLastSent(1, "friend", "скину вечером");
        assertEquals(1, replies.due(0).size(), "A new promise is possible after the old one is fulfilled");
    }

    @Test void confirmedContinuationBeforeDeadlineClosesThePromise() {
        DeferredReplies replies = new DeferredReplies(dir.resolve("deferred"), clock);
        replies.checkLastSent(1, "friend", "отвечу позже");
        replies.checkLastSent(1, "friend", "встречаемся у входа в семь");
        assertTrue(replies.due(0).isEmpty());
    }

    @Test void repeatedPromiseAndTypoCorrectionDoNotRearmReminder() {
        DeferredReplies replies = new DeferredReplies(dir.resolve("deferred"), clock);
        replies.checkLastSent(1, "friend", "позже расскажу");
        assertEquals(1, replies.due(0).size());
        replies.checkLastSent(1, "friend", "*расскажу");
        replies.checkLastSent(1, "friend", "расскажу потом");
        assertTrue(replies.due(0).isEmpty());
    }

    @Test void aFollowUpCannotCreateAnEndlessNudgeChain() {
        Path file = dir.resolve("pending");
        PendingReplies replies = new PendingReplies(file, clock);
        replies.markSent(1, "friend", "как дела?");
        assertTrue(replies.stale(1).isEmpty());
        assertEquals(1, replies.stale(0).size());
        assertTrue(replies.stale(0).isEmpty());
        replies.markSent(1, "friend", "ты где?");
        assertTrue(replies.stale(0).isEmpty());
        replies = new PendingReplies(file, clock);
        replies.markSent(1, "friend", "ау");
        assertTrue(replies.stale(0).isEmpty());
        replies.markAnswered(1);
        replies.markSent(1, "friend", "а завтра?");
        assertEquals(1, replies.stale(0).size());
    }

    @Test void unreadLegacyEntriesCannotTriggerUnverifiedFollowUps() throws Exception {
        Path deferred = dir.resolve("deferred");
        Path pending = dir.resolve("pending");
        String legacy = "1|friend|1|потом напишу\n";
        Files.writeString(deferred, legacy);
        Files.writeString(pending, legacy);
        assertTrue(new DeferredReplies(deferred, clock).due(0).isEmpty());
        assertTrue(new PendingReplies(pending, clock).stale(0).isEmpty());
        assertEquals(legacy, Files.readString(deferred), "Loading must not erase the legacy file");
        assertEquals(legacy, Files.readString(pending));
    }

    @Test void onlyTheThreeDisplayedRemindersAreConsumed() {
        DeferredReplies deferred = new DeferredReplies(dir.resolve("deferred"), clock);
        PendingReplies pending = new PendingReplies(dir.resolve("pending"), clock);
        for (int i = 0; i < 5; i++) {
            deferred.checkLastSent(i, "friend", "напишу позже");
            pending.markSent(i, "friend", "привет");
        }
        assertEquals(3, deferred.due(0).size());
        assertEquals(2, deferred.due(0).size());
        assertTrue(deferred.due(0).isEmpty());
        assertEquals(3, pending.stale(0).size());
        assertEquals(2, pending.stale(0).size());
        assertTrue(pending.stale(0).isEmpty());
    }

    @Test void lookItUpPromisesAreTrackedToo() {
        DeferredReplies replies = new DeferredReplies(dir.resolve("deferred"), clock);
        // «щас гляну» — обещание, а не болтовня: она должна вернуться с ответом
        for (String text : new String[] {"щас гляну", "потом посмотрю", "сейчас проверю",
                "через час скину, погуглю"}) {
            replies.checkLastSent(1, "friend", text);
            assertEquals(1, replies.due(0).size(), text);
            replies.done(1);
        }
        // «не посмотрю» — отрицание, обещания нет
        replies.checkLastSent(1, "friend", "не посмотрю это сегодня");
        assertTrue(replies.due(0).isEmpty());
    }

    @Test void explicitComebackIsDueOnlyAtItsTime() {
        DeferredReplies replies = new DeferredReplies(dir.resolve("deferred"), clock);
        // прочитала и не ответила: вернётся через час, не раньше
        replies.defer(1, "friend", "на паре, потом отвечу", 60 * 60_000L);
        assertTrue(replies.due(0).isEmpty(), "до срока возврата не напоминаем");
        // из-за фиксированных часов в тесте сдвигаем время через новый файл:
        // проще проверить, что после срока запись созревает
        replies = new DeferredReplies(dir.resolve("deferred"),
                Clock.offset(clock, java.time.Duration.ofHours(2)));
        var due = replies.due(0);
        assertEquals(1, due.size(), "после срока пора возвращаться");
        assertEquals("на паре, потом отвечу", due.getFirst().trigger());
        assertTrue(replies.due(0).isEmpty(), "напоминание одноразовое");
    }

    @Test void explicitComebackSurvivesRestartAndIsCancelledByReply() {
        Path file = dir.resolve("deferred");
        DeferredReplies replies = new DeferredReplies(file, clock);
        replies.defer(1, "friend", "прочитала, отвечу позже", 30 * 60_000L);

        DeferredReplies reloaded = new DeferredReplies(file,
                Clock.offset(clock, java.time.Duration.ofMinutes(31)));
        assertEquals(1, reloaded.due(0).size(), "возврат переживает перезапуск");

        // собеседник написал — отложенное больше не нужно
        reloaded.cancelIfAnswered(1);
        assertTrue(reloaded.due(0).isEmpty());
    }
}
