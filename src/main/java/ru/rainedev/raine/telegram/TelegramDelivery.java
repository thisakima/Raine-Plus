package ru.rainedev.raine.telegram;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.tdlight.jni.TdApi;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Tracks server confirmation, never equating local TDLib acceptance with sending. */
public final class TelegramDelivery {
    private static final Logger log = LoggerFactory.getLogger(TelegramDelivery.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long RETENTION_MS = Duration.ofHours(6).toMillis();
    public enum Status { PENDING, CONFIRMED, FAILED, UNKNOWN }
    public record MessageKey(long chatId, long messageId) {}
    public record Entry(String id, String kind, long chatId, String fingerprint, long at,
                        Status status, List<MessageKey> messages) {}

    /** Expected refusal, not a model-quality rejection and never a reason to resend. */
    public static final class Unavailable extends IllegalStateException {
        public Unavailable(String message) { super(message); }
    }

    private final Path file;
    private final Clock clock;
    private final Duration timeout;
    private final Duration settling;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<MessageKey, CompletableFuture<TdApi.Message>> confirmations = new LinkedHashMap<>();
    private final Map<MessageKey, CompletableFuture<TdApi.Story>> stories = new LinkedHashMap<>();
    private final InheritableThreadLocal<Long> expectedGeneration = new InheritableThreadLocal<>();
    private static final class DefinitiveFailure extends IllegalStateException {
        DefinitiveFailure(String message) { super(message); }
    }

    public void bindGeneration(long generation) { expectedGeneration.set(generation); }
    public void clearGeneration() { expectedGeneration.remove(); }
    private final ConcurrentLinkedQueue<TdApi.Message> confirmedMessages = new ConcurrentLinkedQueue<>();
    private boolean connected;
    private boolean storageFailed;
    private long readyAt;
    private long generation;

    /** Момент, когда связь пропала. Ноль — значит онлайн. */
    private long offlineSince;
    /** Когда связь вернулась. Нужна, чтобы показать ей, сколько она была оффлайн. */
    private long lastOfflineStart;
    private long lastOfflineEnd;

    public TelegramDelivery(Path file) {
        this(file, Clock.systemUTC(), Duration.ofSeconds(5), Duration.ofSeconds(3));
    }

    TelegramDelivery(Path file, Clock clock, Duration timeout, Duration settling) {
        this.file = file;
        this.clock = clock;
        this.timeout = timeout;
        this.settling = settling;
        if (file != null && Files.exists(file)) {
            try {
                for (Entry e : JSON.readValue(file.toFile(), Entry[].class)) {
                    entries.put(e.id(), unresolved(e) ? copy(e, Status.UNKNOWN, e.messages()) : e);
                }
                // Bind saved temporary IDs before TDLib starts delivering updates.
                for (Entry e : List.copyOf(entries.values())) {
                    if (unresolved(e) && !e.messages().isEmpty()) {
                        List<? extends CompletableFuture<?>> waits = e.kind().equals("PostStory")
                                ? e.messages().stream().map(this::storyConfirmation).toList()
                                : e.messages().stream().map(key -> confirmation(key).thenApply(message -> {
                                    if (e.kind().equals("SendMessage")) confirmedMessages.add(message);
                                    return message;
                                })).toList();
                        CompletableFuture.allOf(waits.toArray(CompletableFuture[]::new))
                                .whenComplete((ignored, error) -> finish(e.id(), error));
                    }
                }
            } catch (Exception e) {
                storageFailed = true;
                log.error("Telegram delivery journal cannot be read; outgoing actions are paused", e);
            }
        }
    }

    public synchronized void connection(boolean ready) {
        if (connected == ready) return;
        connected = ready;
        readyAt = clock.millis();
        generation++;
        if (ready) {
            // связь вернулась: запоминаем, сколько её не было, — это она
            // должна знать, чтобы не придумывать себе разговоров за это время
            if (offlineSince > 0) {
                lastOfflineStart = offlineSince;
                lastOfflineEnd = clock.millis();
                offlineSince = 0;
            }
        } else if (offlineSince == 0) {
            offlineSince = clock.millis();
        }
        log.info("Telegram transport {} (generation {})", ready ? "synchronizing" : "offline", generation);
    }

    /** Есть ли связь прямо сейчас, без учёта неподтверждённых действий. */
    public synchronized boolean online() {
        return connected;
    }

    /**
     * Сколько времени не было связи и когда она вернулась. Нужно ей самой:
     * человек, у которого полдня не работал мессенджер, помнит об этом и не
     * выдумывает, что кому-то отвечал.
     *
     * @return пустая строка, если обрывов не было
     */
    public synchronized String outageSummary() {
        if (lastOfflineStart <= 0) {
            return "";
        }
        if (offlineSince > 0) {
            return "Telegram has been unreachable since " + localTime(offlineSince)
                    + " and is still down. Nothing you tried to send during that time was delivered.";
        }
        long minutes = Math.max(1, (lastOfflineEnd - lastOfflineStart) / 60_000);
        return "Telegram was unreachable from " + localTime(lastOfflineStart) + " to "
                + localTime(lastOfflineEnd) + " (about " + minutes + " min). "
                + "Nothing was sent or received during that time — you could not have talked to anyone.";
    }

    /** Забыть про последний обрыв: после ночного пересмотра он уже не важен. */
    public synchronized void forgetOutage() {
        lastOfflineStart = 0;
        lastOfflineEnd = 0;
    }

    private String localTime(long millis) {
        return java.time.Instant.ofEpochMilli(millis)
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
    }

    public synchronized long generation() { return generation; }
    public synchronized boolean connected() { return connected; }
    public synchronized boolean ready() {
        return connected && clock.millis() - readyAt >= settling.toMillis()
                && !storageFailed && entries.values().stream().noneMatch(TelegramDelivery::unresolved);
    }

    public synchronized void requireReady() {
        Long expected = expectedGeneration.get();
        if (expected != null && expected != generation) {
            throw new Unavailable("Not submitted: this intention predates a Telegram interruption. "
                    + "Reopen the chat after synchronization before choosing any new action.");
        }
        if (!ready()) throw new Unavailable("Telegram is offline, synchronizing, or an earlier action is unconfirmed. "
                + "No new action was submitted. Wait; do not send substitutes or repeat earlier actions.");
    }

    /** Supplier is called at most once, under the same lock as the connectivity check. */
    public <T extends TdApi.Object> T execute(TdApi.Function<T> request, Supplier<CompletableFuture<T>> send) {
        String id;
        CompletableFuture<T> submitted;
        synchronized (this) {
            requireReady();
            prune();
            String fingerprint = fingerprint(request);
            Entry duplicate = entries.values().stream()
                    .filter(e -> e.fingerprint().equals(fingerprint))
                    .filter(e -> unresolved(e) || clock.millis() - e.at() < duplicateWindow(request))
                    .findFirst().orElse(null);
            if (duplicate != null) throw new Unavailable("Not repeated: this action is already "
                    + duplicate.status() + "; operation_id=" + duplicate.id()
                    + ". Refresh the chat instead of rewording or resending it.");
            id = UUID.randomUUID().toString();
            entries.put(id, new Entry(id, request.getClass().getSimpleName(), chatId(request),
                    fingerprint, clock.millis(), Status.PENDING, List.of()));
            persist(); // Write ahead: a crash must not turn an uncertain submission into a retry.
            try {
                submitted = send.get();
            } catch (RuntimeException error) {
                entries.put(id, copy(entries.get(id), Status.UNKNOWN, List.of()));
                generation++;
                persist();
                throw new Unavailable("Telegram submission outcome UNKNOWN; operation_id=" + id
                        + ". Do not retry automatically.");
            }
        }
        CompletableFuture<T> completed = submitted.thenCompose(result -> confirmResult(id, result));
        // This dependent future includes journal bookkeeping before a successful caller returns.
        CompletableFuture<T> recorded = completed.whenComplete((result, error) -> finish(id, error));
        try {
            return recorded.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            return uncertain(id, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return uncertain(id, true);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            ru.rainedev.raine.core.Fatal.check(cause instanceof RuntimeException r ? r : new IllegalStateException(cause));
            Status status;
            synchronized (this) { status = entries.get(id).status(); }
            throw new Unavailable("Telegram action " + status + "; operation_id=" + id + ". "
                    + "Do not retry automatically; check current chat state. " + safeReason(cause));
        }
    }

    private synchronized <T> T uncertain(String id, boolean interrupted) {
        Entry e = entries.get(id);
        if (unresolved(e)) {
            Status status = e.messages().isEmpty() ? Status.UNKNOWN : Status.PENDING;
            entries.put(id, copy(e, status, e.messages()));
            generation++; // Invalidate the current model turn, even if connectivity never changed.
            persist();
        }
        log.warn("Telegram action {}: operation_id={}, kind={}, chat={}", entries.get(id).status(), id, e.kind(), e.chatId());
        throw new Unavailable("Telegram action " + entries.get(id).status() + "; operation_id=" + id
                + (interrupted ? ". Waiting was interrupted." : ". Confirmation has not arrived within the wait limit.")
                + " This is NOT proof it was sent or failed. It may still complete. Do not resend or follow up.");
    }

    private <T extends TdApi.Object> CompletableFuture<T> confirmResult(String id, T result) {
        if (result instanceof TdApi.Message message) {
            return awaitMessages(id, List.of(message)).thenApply(messages -> {
                @SuppressWarnings("unchecked") T confirmed = (T) messages.getFirst();
                return confirmed;
            });
        }
        if (result instanceof TdApi.Messages messages) {
            List<TdApi.Message> present = new ArrayList<>();
            if (messages.messages != null) {
                for (TdApi.Message message : messages.messages) if (message != null) present.add(message);
            }
            if (messages.messages == null || present.isEmpty() || present.size() != messages.messages.length) {
                return awaitMessages(id, present).thenCompose(ignored ->
                        CompletableFuture.failedFuture(new DefinitiveFailure("Forward batch partially failed; do not repeat the batch")));
            }
            return awaitMessages(id, present).thenApply(confirmed -> {
                messages.messages = confirmed.toArray(TdApi.Message[]::new);
                return result;
            });
        }
        if (result instanceof TdApi.Story story && story.isBeingPosted) {
            synchronized (this) {
                MessageKey key = new MessageKey(story.posterChatId, story.id);
                Entry entry = entries.get(id);
                entries.put(id, copy(entry, entry.status(), List.of(key)));
                persist();
                return storyConfirmation(key).thenApply(confirmed -> {
                    @SuppressWarnings("unchecked") T value = (T) confirmed;
                    return value;
                });
            }
        }
        return CompletableFuture.completedFuture(result);
    }

    private synchronized CompletableFuture<List<TdApi.Message>> awaitMessages(String id, List<TdApi.Message> messages) {
        List<MessageKey> keys = messages.stream()
                .filter(m -> m.sendingState instanceof TdApi.MessageSendingStatePending)
                .map(m -> new MessageKey(m.chatId, m.id)).toList();
        Entry entry = entries.get(id);
        entries.put(id, copy(entry, entry.status(), keys));
        persist();
        List<CompletableFuture<TdApi.Message>> waits = new ArrayList<>();
        for (TdApi.Message message : messages) {
            if (message.sendingState instanceof TdApi.MessageSendingStateFailed) {
                waits.add(CompletableFuture.failedFuture(new DefinitiveFailure("Message sending failed")));
            } else if (message.sendingState instanceof TdApi.MessageSendingStatePending) {
                waits.add(confirmation(new MessageKey(message.chatId, message.id)).thenApply(confirmed -> {
                    if (entry.kind().equals("SendMessage")) confirmedMessages.add(confirmed);
                    return confirmed;
                }));
            } else {
                if (entry.kind().equals("SendMessage")) confirmedMessages.add(message);
                waits.add(CompletableFuture.completedFuture(message));
            }
        }
        return CompletableFuture.allOf(waits.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> {
                    List<TdApi.Message> confirmed = waits.stream().map(CompletableFuture::join).toList();
                    synchronized (this) {
                        Entry latest = entries.get(id);
                        entries.put(id, copy(latest, latest.status(), confirmed.stream()
                                .map(m -> new MessageKey(m.chatId, m.id)).toList()));
                        persist();
                    }
                    return confirmed;
                });
    }

    private synchronized CompletableFuture<TdApi.Message> confirmation(MessageKey key) {
        return confirmations.computeIfAbsent(key, ignored -> new CompletableFuture<>());
    }

    public synchronized void succeeded(long oldId, TdApi.Message message) {
        MessageKey key = new MessageKey(message.chatId, oldId);
        // Keep early updates until SendMessage's response installs its waiter.
        confirmation(key).complete(message);
        trimEarlyUpdates();
    }

    public synchronized void failed(long chatId, long oldId, String reason) {
        confirmation(new MessageKey(chatId, oldId)).completeExceptionally(new DefinitiveFailure(reason));
        trimEarlyUpdates();
    }

    private void trimEarlyUpdates() {
        while (confirmations.size() > 512) {
            MessageKey oldest = confirmations.entrySet().stream().filter(e -> e.getValue().isDone())
                    .filter(e -> entries.values().stream().noneMatch(op -> unresolved(op) && op.messages().contains(e.getKey())))
                    .map(Map.Entry::getKey).findFirst().orElse(null);
            if (oldest == null) break;
            confirmations.remove(oldest);
        }
    }

    private synchronized CompletableFuture<TdApi.Story> storyConfirmation(MessageKey key) {
        return stories.computeIfAbsent(key, ignored -> new CompletableFuture<>());
    }

    public synchronized void storySucceeded(int oldId, TdApi.Story story) {
        storyConfirmation(new MessageKey(story.posterChatId, oldId)).complete(story);
        trimStoryUpdates();
    }

    public synchronized void storyFailed(long chatId, int oldId, String reason) {
        storyConfirmation(new MessageKey(chatId, oldId)).completeExceptionally(new DefinitiveFailure(reason));
        trimStoryUpdates();
    }

    private void trimStoryUpdates() {
        while (stories.size() > 256) {
            MessageKey oldest = stories.entrySet().stream().filter(e -> e.getValue().isDone())
                    .map(Map.Entry::getKey).findFirst().orElse(null);
            if (oldest == null) break;
            stories.remove(oldest);
        }
    }

    private synchronized void finish(String id, Throwable error) {
        Entry e = entries.get(id);
        if (e == null || !unresolved(e)) return;
        Throwable root = error;
        while (root instanceof java.util.concurrent.CompletionException && root.getCause() != null) root = root.getCause();
        boolean rejected = root instanceof DefinitiveFailure
                || root instanceof it.tdlight.client.TelegramError api && api.getErrorCode() > 0 && api.getErrorCode() < 500;
        Status status = root == null ? Status.CONFIRMED : rejected ? Status.FAILED : Status.UNKNOWN;
        if (status == Status.UNKNOWN) generation++;
        entries.put(id, copy(e, status, e.messages()));
        persist();
        log.info("Telegram action {}: operation_id={}, kind={}, chat={}", status, id, e.kind(), e.chatId());
    }

    /** Retry reads only, never submissions. Unknown IDs remain blocked for operator reconciliation. */
    public void reconcile(java.util.function.Function<MessageKey, CompletableFuture<TdApi.Message>> lookup,
                          java.util.function.Function<MessageKey, CompletableFuture<TdApi.Story>> storyLookup) {
        List<MessageKey> pending;
        synchronized (this) {
            pending = entries.values().stream().filter(TelegramDelivery::unresolved)
                    .filter(e -> !e.kind().equals("PostStory"))
                    .flatMap(e -> e.messages().stream()).distinct().toList();
            entries.values().stream().filter(TelegramDelivery::unresolved).filter(e -> e.kind().equals("PostStory"))
                    .flatMap(e -> e.messages().stream()).forEach(key -> storyLookup.apply(key).thenAccept(story -> {
                        if (!story.isBeingPosted) storySucceeded((int) key.messageId(), story);
                    }).exceptionally(error -> null));
        }
        for (MessageKey key : pending) {
            lookup.apply(key).thenAccept(message -> {
                if (message.sendingState == null) succeeded(key.messageId(), message);
                else if (message.sendingState instanceof TdApi.MessageSendingStateFailed)
                    failed(key.chatId(), key.messageId(), "TDLib reports sending failed");
            }).exceptionally(error -> {
                log.warn("Cannot reconcile Telegram message chat={}, temporary_id={}; outcome remains unconfirmed",
                        key.chatId(), key.messageId());
                return null;
            });
        }
    }

    public List<TdApi.Message> drainConfirmed() {
        List<TdApi.Message> result = new ArrayList<>();
        TdApi.Message message;
        while ((message = confirmedMessages.poll()) != null) result.add(message);
        return result;
    }

    public synchronized String statusPrompt() {
        StringBuilder text = new StringBuilder();
        String outage = outageSummary();
        if (!outage.isBlank()) {
            text.append("[Telegram outage] ").append(outage).append('\n');
        }
        if (!ready()) {
            // сказать прямо и до таблицы операций: иначе она читает журнал
            // и решает, что всё в порядке
            text.append("[Telegram is NOT reachable right now] You cannot send messages, react, post stories "
                    + "or do anything else in Telegram. Do not try, do not promise anyone you will write, "
                    + "and do not record in your diary that you sent or replied to anything — it did not "
                    + "happen. Live your day as a person without a working messenger: think, take photos, "
                    + "write in your diary about the day itself. When the connection comes back you will see "
                    + "fresh messages and can answer them then.\n");
        }
        text.append("[Telegram delivery facts] Only CONFIRMED means accepted by the server, "
                + "not read by the recipient. PENDING/UNKNOWN actions must not be repeated or treated as completed.\n");
        var recent = new ArrayList<>(entries.values());
        for (Entry e : recent.subList(Math.max(0, recent.size() - 20), recent.size())) {
            text.append(e.id()).append(" ").append(e.kind()).append(" chat=").append(e.chatId())
                    .append(" ").append(e.status()).append(" message_ids=").append(e.messages()).append('\n');
        }
        return text.toString();
    }

    private void prune() {
        entries.values().removeIf(e -> !unresolved(e) && clock.millis() - e.at() > RETENTION_MS);
        // Bound terminal history, but never silently forget an uncertain operation.
        while (entries.size() > 256) {
            String oldest = entries.values().stream().filter(e -> !unresolved(e)).map(Entry::id).findFirst().orElse(null);
            if (oldest == null) break;
            entries.remove(oldest);
        }
    }

    private static boolean unresolved(Entry e) { return e.status() == Status.PENDING || e.status() == Status.UNKNOWN; }
    private static Entry copy(Entry e, Status status, List<MessageKey> keys) {
        return new Entry(e.id(), e.kind(), e.chatId(), e.fingerprint(), e.at(), status, keys);
    }

    private void persist() {
        if (file == null) return;
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            JSON.writeValue(temp.toFile(), entries.values());
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception error) {
            storageFailed = true;
            throw new Unavailable("Delivery journal cannot be saved; outgoing actions paused. Check disk access.");
        }
    }

    private static String safeReason(Throwable error) {
        return error == null ? "" : String.valueOf(error.getMessage());
    }

    private static long chatId(TdApi.Function<?> request) {
        try { return request.getClass().getField("chatId").getLong(request); }
        catch (ReflectiveOperationException ignored) { return 0; }
    }

    private static long duplicateWindow(TdApi.Function<?> request) {
        if (request instanceof TdApi.SendMessage send && !(send.inputMessageContent instanceof TdApi.InputMessageText))
            return RETENTION_MS;
        return Duration.ofMinutes(10).toMillis();
    }

    private static String fingerprint(TdApi.Function<?> request) {
        try {
            Object identity = request;
            if (request instanceof TdApi.SendMessage send) {
                Object content = switch (send.inputMessageContent) {
                    case TdApi.InputMessagePhoto p -> p.photo.photo;
                    case TdApi.InputMessageVideo v -> v.video.video;
                    case TdApi.InputMessageVoiceNote v -> v.voiceNote;
                    case TdApi.InputMessageSticker s -> s.sticker;
                    default -> send.inputMessageContent;
                };
                identity = List.of("SendMessage", send.chatId, content);
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(identity)));
        } catch (Exception error) {
            throw new Unavailable("Cannot identify this Telegram action safely; it was not submitted.");
        }
    }
}
