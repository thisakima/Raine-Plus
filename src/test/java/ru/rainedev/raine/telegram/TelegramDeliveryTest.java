package ru.rainedev.raine.telegram;

import static org.junit.jupiter.api.Assertions.*;

import it.tdlight.jni.TdApi;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TelegramDeliveryTest {
    @TempDir Path dir;

    private static final class TestClock extends Clock {
        long millis = 100_000;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(millis); }
    }

    private TelegramDelivery tracker(Path file) {
        TelegramDelivery tracker = new TelegramDelivery(file, Clock.systemUTC(), Duration.ofMillis(15), Duration.ZERO);
        tracker.connection(true);
        return tracker;
    }

    private static TdApi.SendMessage text(long chat, String text) {
        TdApi.SendMessage send = new TdApi.SendMessage();
        send.chatId = chat;
        TdApi.InputMessageText content = new TdApi.InputMessageText();
        content.text = new TdApi.FormattedText(text, new TdApi.TextEntity[0]);
        send.inputMessageContent = content;
        return send;
    }

    private static TdApi.Message message(long chat, long id, boolean pending) {
        TdApi.Message message = new TdApi.Message();
        message.chatId = chat;
        message.id = id;
        message.isOutgoing = true;
        if (pending) message.sendingState = new TdApi.MessageSendingStatePending();
        return message;
    }

    private static TdApi.AddMessageReaction reaction() {
        TdApi.AddMessageReaction request = new TdApi.AddMessageReaction();
        request.chatId = 1;
        request.messageId = 42;
        request.reactionType = new TdApi.ReactionTypeEmoji("🔥");
        return request;
    }

    @Test void offlineAndSettlingNeverSubmit() {
        TestClock clock = new TestClock();
        TelegramDelivery tracker = new TelegramDelivery(null, clock, Duration.ofMillis(15), Duration.ofSeconds(3));
        AtomicInteger submissions = new AtomicInteger();
        var send = (java.util.function.Supplier<CompletableFuture<TdApi.Message>>) () -> {
            submissions.incrementAndGet();
            return CompletableFuture.completedFuture(message(1, 100, false));
        };
        assertThrows(TelegramDelivery.Unavailable.class, () -> tracker.execute(text(1, "hello"), send));
        tracker.connection(true);
        assertThrows(TelegramDelivery.Unavailable.class, () -> tracker.execute(text(1, "hello"), send));
        assertEquals(0, submissions.get());
        clock.millis += 3000;
        assertEquals(100, tracker.execute(text(1, "hello"), send).id);
        tracker.connection(false);
        assertThrows(TelegramDelivery.Unavailable.class, () -> tracker.execute(text(1, "new"), send));
        assertEquals(1, submissions.get());
    }

    @Test void pendingIsNotSuccessAndLateConfirmationDoesNotResend() {
        TelegramDelivery tracker = tracker(null);
        AtomicInteger submissions = new AtomicInteger();
        var failure = assertThrows(TelegramDelivery.Unavailable.class, () -> tracker.execute(text(1, "hello"), () -> {
            submissions.incrementAndGet();
            return CompletableFuture.completedFuture(message(1, -10, true));
        }));
        assertTrue(failure.getMessage().contains("PENDING"));
        assertFalse(tracker.ready());
        assertTrue(tracker.drainConfirmed().isEmpty());
        assertThrows(TelegramDelivery.Unavailable.class, () -> tracker.execute(text(2, "other"), () -> {
            submissions.incrementAndGet();
            return CompletableFuture.completedFuture(message(2, 100, false));
        }));
        tracker.succeeded(-10, message(1, 200, false));
        tracker.succeeded(-10, message(1, 200, false));
        assertTrue(tracker.ready());
        assertEquals(List.of(200L), tracker.drainConfirmed().stream().map(m -> m.id).toList());
        assertEquals(1, submissions.get());
        assertTrue(tracker.statusPrompt().contains("CONFIRMED message_ids=[MessageKey[chatId=1, messageId=200]]"));
    }

    @Test void earlyConfirmationReturnsTheFinalId() {
        TelegramDelivery tracker = tracker(null);
        TdApi.Message result = tracker.execute(text(1, "hello"), () -> {
            tracker.succeeded(-10, message(1, 200, false));
            return CompletableFuture.completedFuture(message(1, -10, true));
        });
        assertEquals(200, result.id);
        assertEquals(1, tracker.drainConfirmed().size());
    }

    @Test void temporaryIdsAreScopedToChats() {
        TelegramDelivery tracker = tracker(null);
        tracker.succeeded(-10, message(2, 99, false));
        assertThrows(TelegramDelivery.Unavailable.class, () -> tracker.execute(text(1, "hello"),
                () -> CompletableFuture.completedFuture(message(1, -10, true))));
        assertFalse(tracker.ready());
        assertTrue(tracker.drainConfirmed().isEmpty());
        tracker.failed(1, -10, "rejected");
        assertTrue(tracker.ready());
        assertTrue(tracker.statusPrompt().contains(" FAILED "));
    }

    @Test void unknownReactionRetainsItsFutureAndBlocksFurtherWrites() {
        TelegramDelivery tracker = tracker(null);
        CompletableFuture<TdApi.Ok> response = new CompletableFuture<>();
        var failure = assertThrows(TelegramDelivery.Unavailable.class,
                () -> tracker.execute(reaction(), () -> response));
        assertTrue(failure.getMessage().contains("UNKNOWN"));
        assertFalse(response.isCancelled());
        assertFalse(tracker.ready());
        response.complete(new TdApi.Ok());
        assertTrue(tracker.ready());
        assertTrue(tracker.statusPrompt().contains(" CONFIRMED message_ids="));
    }

    @Test void failedReactionFutureNeverClaimsSuccess() {
        TelegramDelivery tracker = tracker(null);
        var failure = assertThrows(TelegramDelivery.Unavailable.class, () -> tracker.execute(reaction(),
                () -> CompletableFuture.failedFuture(new IllegalStateException("connection lost"))));
        assertTrue(failure.getMessage().contains("UNKNOWN"));
        assertFalse(tracker.ready());
        assertFalse(tracker.statusPrompt().contains(" CONFIRMED message_ids="));
    }

    @Test void generationChangeRejectsAnOldIntentionEvenAfterReconnect() {
        TelegramDelivery tracker = tracker(null);
        tracker.bindGeneration(tracker.generation());
        tracker.connection(false);
        tracker.connection(true);
        assertTrue(tracker.ready());
        assertThrows(TelegramDelivery.Unavailable.class, () -> tracker.execute(text(1, "stale"), () -> {
            fail("Stale intention reached TDLib");
            return null;
        }));
        tracker.clearGeneration();
        assertDoesNotThrow(tracker::requireReady);
    }

    @Test void backgroundThreadsInheritTheGenerationGuard() throws Exception {
        TelegramDelivery tracker = tracker(null);
        tracker.bindGeneration(tracker.generation());
        CompletableFuture<Void> release = new CompletableFuture<>();
        CompletableFuture<Boolean> refused = new CompletableFuture<>();
        Thread thread = Thread.ofVirtual().start(() -> {
            release.join();
            try { tracker.requireReady(); refused.complete(false); }
            catch (TelegramDelivery.Unavailable e) { refused.complete(true); }
        });
        tracker.connection(false);
        tracker.connection(true);
        release.complete(null);
        thread.join();
        assertTrue(refused.join());
    }

    @Test void journalRestoresUncertaintyAndReconcilesOnlyByReading() throws Exception {
        Path file = dir.resolve("delivery.json");
        TelegramDelivery tracker = tracker(file);
        assertThrows(TelegramDelivery.Unavailable.class, () -> tracker.execute(text(1, "private message body"),
                () -> CompletableFuture.completedFuture(message(1, -10, true))));
        assertFalse(Files.readString(file).contains("private message body"));
        TelegramDelivery restored = tracker(file);
        assertFalse(restored.ready());
        AtomicInteger reads = new AtomicInteger();
        restored.reconcile(key -> {
            reads.incrementAndGet();
            assertEquals(new TelegramDelivery.MessageKey(1, -10), key);
            return CompletableFuture.completedFuture(message(1, 200, false));
        }, key -> { throw new AssertionError("Not a story"); });
        assertEquals(1, reads.get());
        assertTrue(restored.ready());
        assertEquals(1, restored.drainConfirmed().size());
    }

    @Test void unknownWithoutIdsStaysBlockedAcrossRestart() {
        Path file = dir.resolve("delivery.json");
        TelegramDelivery tracker = tracker(file);
        assertThrows(TelegramDelivery.Unavailable.class, () -> tracker.execute(reaction(), CompletableFuture::new));
        TelegramDelivery restored = tracker(file);
        restored.reconcile(key -> { throw new AssertionError(); }, key -> { throw new AssertionError(); });
        assertFalse(restored.ready());
    }

    @Test void corruptJournalPreventsSubmission() throws Exception {
        Path file = dir.resolve("delivery.json");
        Files.writeString(file, "broken");
        TelegramDelivery tracker = tracker(file);
        assertThrows(TelegramDelivery.Unavailable.class, () -> tracker.execute(reaction(), () -> {
            fail("Cannot submit without a trustworthy journal");
            return null;
        }));
    }

    @Test void failedJournalWritePreventsSubmission() throws Exception {
        Path parent = dir.resolve("not-a-directory");
        Files.writeString(parent, "file");
        TelegramDelivery tracker = tracker(parent.resolve("delivery.json"));
        assertThrows(TelegramDelivery.Unavailable.class, () -> tracker.execute(reaction(), () -> {
            fail("Write-ahead journal must succeed first");
            return null;
        }));
        assertFalse(tracker.ready());
    }

    @Test void samePhotoWithDifferentCaptionIsNotResubmitted() {
        TelegramDelivery tracker = tracker(null);
        TdApi.SendMessage send = text(1, "ignored");
        TdApi.InputMessagePhoto photo = new TdApi.InputMessagePhoto();
        photo.photo = new TdApi.InputPhoto();
        photo.photo.photo = new TdApi.InputFileLocal("/gallery/photo.jpg");
        photo.caption = new TdApi.FormattedText("first", new TdApi.TextEntity[0]);
        send.inputMessageContent = photo;
        tracker.execute(send, () -> CompletableFuture.completedFuture(message(1, 100, false)));
        photo.caption.text = "reworded";
        assertThrows(TelegramDelivery.Unavailable.class, () -> tracker.execute(send, () -> {
            fail("The file is already confirmed in this chat");
            return null;
        }));
    }

    @Test void partialForwardBatchCannotBeRepeated() {
        TelegramDelivery tracker = tracker(null);
        TdApi.ForwardMessages request = new TdApi.ForwardMessages();
        request.chatId = 1;
        request.fromChatId = 2;
        request.messageIds = new long[] {10, 20};
        TdApi.Messages result = new TdApi.Messages();
        result.messages = new TdApi.Message[] {message(1, 100, false), null};
        assertThrows(TelegramDelivery.Unavailable.class,
                () -> tracker.execute(request, () -> CompletableFuture.completedFuture(result)));
        assertTrue(tracker.ready());
        assertThrows(TelegramDelivery.Unavailable.class, () -> tracker.execute(request, () -> {
            fail("Partial success must not cause a duplicate forwarding batch");
            return null;
        }));
    }

    @Test void storyWaitsForPostSuccessOrFailure() {
        TelegramDelivery tracker = tracker(null);
        TdApi.Story story = new TdApi.Story();
        story.posterChatId = 1;
        story.id = -1;
        story.isBeingPosted = true;
        assertThrows(TelegramDelivery.Unavailable.class, () -> tracker.execute(new TdApi.PostStory(),
                () -> CompletableFuture.completedFuture(story)));
        assertFalse(tracker.ready());
        TdApi.Story posted = new TdApi.Story();
        posted.posterChatId = 1;
        posted.id = 100;
        tracker.storySucceeded(-1, posted);
        assertTrue(tracker.ready());
        assertTrue(tracker.statusPrompt().contains(" CONFIRMED message_ids="));

        TelegramDelivery failed = tracker(null);
        assertThrows(TelegramDelivery.Unavailable.class, () -> failed.execute(new TdApi.PostStory(),
                () -> CompletableFuture.completedFuture(story)));
        failed.storyFailed(1, -1, "not allowed");
        assertTrue(failed.ready());
        assertTrue(failed.statusPrompt().contains(" FAILED "));
    }

    @Test void editsDoNotCreateNewUnansweredReminders() {
        TelegramDelivery tracker = tracker(null);
        tracker.execute(new TdApi.EditMessageText(), () -> CompletableFuture.completedFuture(message(1, 100, false)));
        assertTrue(tracker.drainConfirmed().isEmpty());
    }

    /** Обрыв связи фиксируется и сообщается ей — иначе она выдумает разговоры за это время. */
    @Test void outageIsTrackedAndReported(@TempDir Path dir) {
        TestClock clock = new TestClock();
        TelegramDelivery d = new TelegramDelivery(dir.resolve("d.json"), clock,
                Duration.ofSeconds(1), Duration.ZERO);
        d.connection(true);
        assertEquals("", d.outageSummary(), "без обрывов рассказывать нечего");

        d.connection(false);
        assertEquals("", d.outageSummary(), "пока offline, длительность ещё не известна");

        clock.millis += 30 * 60 * 1000L;   // полчаса без связи
        d.connection(true);
        String outage = d.outageSummary();
        assertTrue(outage.contains("unreachable"), "должно сказать про недоступность: " + outage);
        assertTrue(outage.contains("30 min"), "длительность должна быть видна: " + outage);
        assertTrue(outage.contains("Nothing was sent"), "должно сказать, что ничего не ушло: " + outage);
        assertTrue(outage.contains("could not have talked"), "и что разговоров не было: " + outage);
    }

    @Test void statusPromptWarnsWhenOffline(@TempDir Path dir) {
        TelegramDelivery d = new TelegramDelivery(dir.resolve("d.json"), new TestClock(),
                Duration.ofSeconds(1), Duration.ZERO);
        d.connection(true);
        d.connection(false);
        String prompt = d.statusPrompt();
        assertTrue(prompt.contains("NOT reachable"), "она должна явно видеть, что связи нет: " + prompt);
        assertTrue(prompt.contains("did not happen"), "и что записывать об этом нельзя: " + prompt);
    }

    @Test void statusPromptIsQuietWhenOnline(@TempDir Path dir) {
        TestClock clock = new TestClock();
        TelegramDelivery d = new TelegramDelivery(dir.resolve("d.json"), clock,
                Duration.ofSeconds(1), Duration.ZERO);
        d.connection(true);
        clock.millis += 60_000;   // прошло settling — связь точно готова
        String prompt = d.statusPrompt();
        assertFalse(prompt.contains("NOT reachable"), "при связи пугать нечем: " + prompt);
        assertTrue(prompt.contains("[Telegram delivery facts]"), prompt);
    }

    @Test void forgetOutageClearsTheSummary(@TempDir Path dir) {
        TestClock clock = new TestClock();
        TelegramDelivery d = new TelegramDelivery(dir.resolve("d.json"), clock,
                Duration.ofSeconds(1), Duration.ZERO);
        d.connection(true);
        d.connection(false);
        clock.millis += 30 * 60 * 1000L;
        d.connection(true);
        assertFalse(d.outageSummary().isEmpty(), "обрыв записан");
        d.forgetOutage();
        assertEquals("", d.outageSummary(), "после forget память об обрыве стёрта");
    }

    @Test void outageIsVisibleInStatusPromptAfterReconnect(@TempDir Path dir) {
        TestClock clock = new TestClock();
        TelegramDelivery d = new TelegramDelivery(dir.resolve("d.json"), clock,
                Duration.ofSeconds(1), Duration.ZERO);
        d.connection(true);
        d.connection(false);
        clock.millis += 60_000;
        d.connection(true);
        clock.millis += 60_000;
        assertTrue(d.statusPrompt().contains("[Telegram outage]"),
                "после восстановления она должна знать, что был обрыв: " + d.statusPrompt());
    }
}
