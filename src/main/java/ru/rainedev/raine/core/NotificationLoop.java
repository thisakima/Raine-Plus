package ru.rainedev.raine.core;

import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.llm.ChatResponse;
import ru.rainedev.raine.llm.LlmClient;
import ru.rainedev.raine.llm.Message;
import ru.rainedev.raine.llm.ToolCall;
import ru.rainedev.raine.memory.Memory;
import ru.rainedev.raine.ui.WebUi;

/**
 * Ядро поведения: очередь уведомлений и обработка каждого до тех пор,
 * пока Raine не решит, что делать больше нечего.
 * <p>
 * Ход всегда заканчивается вызовом {@code wait} или {@code pause}. Ответ
 * обычным текстом действием не считается: его никто не увидит, и модели
 * об этом напоминают.
 */
public final class NotificationLoop {

    private static final Logger log = LoggerFactory.getLogger(NotificationLoop.class);

    /** Страховка от зацикливания: живой ход столько шагов не занимает. */
    private static final int MAX_STEPS_PER_NOTIFICATION = 24;

    /** Сколько раз возвращаться к уведомлению, пока не вернётся сеть. */
    private static final int MAX_RETRIES = 5;

    /** Пауза между такими возвращениями: сеть здесь пропадает на минуты, не на секунды. */
    private Duration retryPause = Duration.ofSeconds(60);

    /** Сигнал «пора заканчивать»: кладётся в очередь, чтобы разбудить ожидание. */
    private static final Notification STOP = new Notification("", new Toolbox());

    private volatile boolean stopping;
    private final java.util.concurrent.CountDownLatch stopped = new java.util.concurrent.CountDownLatch(1);

    /** Как часто в тишине переспрашивать себя, не пора ли отдохнуть. */
    private java.time.Duration idleCheck = java.time.Duration.ofMinutes(1);

    private static final String ASK_REMINDER = """
            [system] Have you called #ask yet this turn? If the message involves personal topics, past events, \
            questions, or people you know — call #ask BEFORE send_telegram_message.""";

    private static final String ASK_MISSED = """
            [system] Note: you sent a message without consulting #ask this turn. Next time, call #ask before \
            send_telegram_message to enrich your response with memories and context.""";

    private static final String BE_TOOL_CENTRIC = """
            Nice thoughts! However you should be tool-centric. Make sure you made tool calls. \
            The message you provided is not visible to anyone but you. Call #wait if you are unsure.""";

    private static final int MAX_QUEUED = 100;
    private final LinkedBlockingDeque<Notification> queue = new LinkedBlockingDeque<>();
    private java.util.function.BooleanSupplier available = () -> true;
    /** Жить своим днём, даже когда Telegram недоступен. */
    private boolean offlineLife;
    private java.util.function.LongSupplier transportGeneration = () -> 0;
    private java.util.function.LongConsumer bindGeneration = ignored -> {};
    private java.util.function.Supplier<String> deliveryFacts = () -> "";
    private Runnable onReconnect = () -> {};
    private Runnable onResumed = () -> {};
    private volatile long observedGeneration;

    /** Текущий chat_id, в котором идёт разговор (0 = ни в каком). */
    private volatile long currentChatId = 0;

    private final List<java.util.function.Consumer<Notification>> onDiscard = new java.util.concurrent.CopyOnWriteArrayList<>();

    public void transport(java.util.function.BooleanSupplier available, java.util.function.LongSupplier generation,
                          java.util.function.LongConsumer bind, java.util.function.Supplier<String> facts,
                          Runnable onReconnect) {
        this.available = available;
        this.transportGeneration = generation;
        this.bindGeneration = bind;
        this.deliveryFacts = facts;
        this.onReconnect = onReconnect;
        this.observedGeneration = Long.MIN_VALUE;
    }

    public void onTransportResumed(Runnable action) { onResumed = action; }

    /**
     * Жить ли свой день, пока Telegram недоступен.
     * <p>
     * Без этого при обрыве связи цикл замирает целиком: ни фото, ни дневника,
     * ни браузера — как будто у неё отключили не мессенджер, а жизнь. С этим
     * она продолжает жить, а всё, что требует Telegram, отсекается на уровне
     * инструментов, и она получает честный отказ вместо ложного успеха.
     */
    public void offlineLife(boolean enabled) { this.offlineLife = enabled; }

    /** Можно ли принимать её собственные порывы: связи нет, но жить можно. */
    public boolean acceptsImpulses() { return available() || offlineLife; }

    /**
     * Пока Telegram недоступен, берём из очереди только её собственные порывы:
     * жить-то надо, а входящие за время обрыва всё равно устарели — при
     * возврате связи они и так сбрасываются.
     */
    private Notification pollOwnImpulse() throws InterruptedException {
        long deadline = System.nanoTime() + idleCheck.toNanos();
        while (!stopping && !Thread.currentThread().isInterrupted()) {
            Notification next = queue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (next == null) {
                if (System.nanoTime() >= deadline) return null;
                continue;
            }
            if (next == STOP) return next;
            if (next.impulse()) return next;
            // входящее сообщение за время обрыва: не копим и не отвечаем залпом
            discarded(next);
        }
        return null;
    }

    long generation() { return observedGeneration; }

    public boolean available() { return available.getAsBoolean() && observedGeneration == transportGeneration.getAsLong(); }
    public void onNotificationDiscarded(java.util.function.Consumer<Notification> action) { onDiscard.add(action); }

    private void discarded(Notification notification) {
        for (var listener : onDiscard) {
            try { listener.accept(notification); }
            catch (RuntimeException e) { log.warn("Notification discard listener failed", e); }
        }
    }

    /** Run only on the brain thread, after TDLib reports Ready and pending sends resolve. */
    boolean refreshTransport() {
        if (!available.getAsBoolean()) return false;
        long generation = transportGeneration.getAsLong();
        if (generation != observedGeneration) {
            synchronized (queue) {
                for (Notification notification : List.copyOf(queue)) {
                    if (notification != STOP && !notification.ownText() && queue.remove(notification)) discarded(notification);
                }
            }
            if (active != null) {
                Notification interrupted = active.notification;
                abortTransport(active);
                notifyDone(interrupted);
            } else if (retrying != null) {
                discarded(retrying);
                notifyDone(retrying);
            }
            retrying = null;
            retries = 0;
            context.add(Message.user("Telegram connectivity changed. Old unsent intentions were discarded. "
                    + "Refresh chat history; do not replay old messages or assume pending actions completed. "
                    + "If Telegram was offline for a while, you could not have talked to anyone during "
                    + "that time — do not invent conversations or record them in your diary."));
            onReconnect.run();
            if (!available.getAsBoolean() || generation != transportGeneration.getAsLong()) return false;
            observedGeneration = generation;
        }
        onResumed.run();
        return available.getAsBoolean() && generation == transportGeneration.getAsLong();
    }

    private void abortTransport(Turn turn) {
        context.add(Message.user("Telegram activity paused — connection lost. Do not claim unconfirmed actions "
                + "happened or repeat them. Remaining planned actions were not executed. You cannot send "
                + "messages, react, or do anything in Telegram right now. Live your day without it and "
                + "re-read the chat after synchronization."));
        // Preserve an incoming chat's need for attention, not the generated response/tool calls.
        turn.notification.tools().all().stream().filter(t -> "open".equals(t.name())).findFirst().ifPresent(open -> {
            String original = turn.notification.text().replaceFirst("(?s)^\\[Interrupted conversation]\\n", "");
            submit(new Notification("[Interrupted conversation]\n" + original, new Toolbox(open), true));
        });
        discarded(turn.notification);
        active = null;
    }
    private final List<Message> context = new ArrayList<>();

    /** Сколько раз подряд текущее уведомление уже спотыкалось о пропавшую сеть. */
    private int retries;
    private Turn active;
    private Notification retrying;
    private boolean suspended;
    private java.util.function.BiConsumer<Notification, RuntimeException> onFailure = (n, e) -> {};

    private static final class Turn {
        final Notification notification;
        final long generation;
        final long startedAt = System.currentTimeMillis();
        long tokens;
        int step;
        boolean recall = true;
        boolean asked;
        boolean prepared;
        Turn(Notification notification, long generation) {
            this.notification = notification;
            this.generation = generation;
        }
    }

    public void onNotificationFailed(java.util.function.BiConsumer<Notification, RuntimeException> action) {
        onFailure = action;
    }

    private final LlmClient llm;
    private final SystemPrompt systemPrompt;
    private final Toolbox alwaysAvailable;
    private final long contextTokenLimit;

    /** Что делать с распухшим контекстом. Позже здесь появится запись в дневник. */
    private Runnable onContextOverflow = () -> {};

    private Memory memory = Memory.NONE;

    private Rest rest;

    /** Ход закончился — она отложила телефон. */
    private Runnable onIdle = () -> { };

    /** Что сделать, когда ход закончен. Слушателей несколько: закрыть чаты, снять признак порыва. */
    private final List<java.util.function.Consumer<Notification>> onNotificationDone = new ArrayList<>();

    @FunctionalInterface
    public interface SystemPrompt {
        String text();
    }

    public NotificationLoop(LlmClient llm, SystemPrompt systemPrompt, Toolbox alwaysAvailable, long contextTokenLimit) {
        this.llm = llm;
        this.systemPrompt = systemPrompt;
        this.alwaysAvailable = alwaysAvailable;
        this.contextTokenLimit = contextTokenLimit;
    }

    public void onContextOverflow(Runnable action) {
        this.onContextOverflow = action;
    }

    public void memory(Memory memory) {
        this.memory = memory;
    }

    public void rest(Rest rest) {
        this.rest = rest;
    }

    public void onIdle(Runnable action) {
        this.onIdle = action;
    }

    /**
     * Просит закончить: начатый ход доводится до конца, новые не берутся.
     * Обрывать разговор на полуслове нельзя — то, что уже сказано, ещё
     * не сохранено, и человек останется с недописанной фразой.
     */
    public void stop() {
        stopping = true;
        if (rest != null) {
            rest.stop();   // если она спит до утра, будим: закрываемся
        }
        queue.addFirst(STOP);   // будим ожидание, если она стоит в тишине
    }

    /** @return успела ли закончить сама */
    public boolean awaitStopped(java.time.Duration limit) {
        try {
            return stopped.await(limit.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Для проверок: как долго молчание считается тишиной. */
    void idleCheck(java.time.Duration howOften) {
        this.idleCheck = howOften;
    }

    /** Для проверок: сколько ждать, прежде чем вернуться к уведомлению. */
    void retryPause(Duration pause) {
        this.retryPause = pause;
    }

    public void onNotificationDone(java.util.function.Consumer<Notification> action) {
        this.onNotificationDone.add(action);
    }

    public void submit(Notification notification) {
        enqueue(notification, false);
    }

    private void enqueue(Notification notification, boolean first) {
        synchronized (queue) {
            String key = notificationKey(notification);
            if (!key.isEmpty()) {
                for (Notification old : List.copyOf(queue)) {
                    if (notificationKey(old).equals(key) && queue.remove(old)) discarded(old);
                }
            }
            if (queue.size() >= MAX_QUEUED) {
                Notification old = queue.pollLast();
                if (old != null) discarded(old);
            }
            if (first) queue.addFirst(notification); else queue.addLast(notification);
        }
    }

    private static String notificationKey(Notification notification) {
        var match = java.util.regex.Pattern.compile("<notification chat_id=\"-?\\d+\">").matcher(notification.text());
        if (!match.find()) return "";
        if (notification.text().startsWith("[Interrupted conversation]")) return "resume:" + match.group();
        return (notification.ownText() ? "context:" : "chat:") + match.group();
    }

    /** Срочное уведомление — попадёт в начало очереди. */
    public void submitFirst(Notification notification) {
        enqueue(notification, true);
    }

    /** Устанавливает текущий chat_id (вызывается из инструмента open). */
    public void setCurrentChatId(long chatId) {
        if (chatId != 0 && chatId != currentChatId) {
            log.debug("Chat changed to {} — clearing context", chatId);
            currentChatId = chatId;
            context.clear();
        }
    }

    /** Убирает устаревшие уведомления — например, когда чат уже открыт вручную. */
    public void discardMatching(String substring) {
        synchronized (queue) {
            for (Notification notification : List.copyOf(queue)) {
                if (!notification.ownText() && notification.text().contains(substring) && queue.remove(notification)) {
                    discarded(notification);
                }
            }
        }
    }

    /** Сколько уведомлений ждёт разбора. */
    public int queued() {
        return queue.size();
    }

    public String latestContextText() {
        return context.isEmpty() ? "" : String.valueOf(context.getLast().content());
    }

    public List<Message> context() {
        List<Message> snapshot = new ArrayList<>(context);
        String facts = deliveryFacts.get();
        if (!facts.isBlank() && !snapshot.isEmpty()) snapshot.add(Message.user(facts));
        return List.copyOf(snapshot);
    }

    public void clearContext() {
        context.clear();
    }

    /** Бесконечный цикл. Прерывается только остановкой потока. */
    public void run() {        while (!Thread.currentThread().isInterrupted()) {
            Notification taken = null;
            try {
                if (stopping) { stopped.countDown(); return; }
                boolean transportUp = refreshTransport();
                if (!transportUp && !offlineLife) {
                    Thread.sleep(250);
                    continue;
                }
                if (rest != null) {
                    // отходит перед тем, как взяться за уведомление: накопившееся
                    // прочитается разом, как это и бывает у человека
                    rest.maybeRest();
                }
                // ждём не бесконечно: разговор затих — значит через минуту снова
                // проверим, не пора ли спать. Иначе разбуженная среди ночи она
                // так и осталась бы на ногах до утра, пока ей не напишут ещё раз
                Notification notification;
                if (retrying != null) {
                    notification = retrying;
                    retrying = null;
                } else if (!transportUp) {
                    // Telegram недоступен, но день идёт: берём только её
                    // собственные порывы — фото, дневник, браузер, мысли.
                    // Входящие во время обрыва всё равно сбросились бы при
                    // возврате связи, поэтому их не копим.
                    notification = pollOwnImpulse();
                    if (notification == null) {
                        continue;
                    }
                } else {
                    notification = queue.poll(idleCheck.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
                }
                if (stopping) {
                    log.info("Заканчиваю: новых уведомлений больше не беру");
                    stopped.countDown();
                    return;
                }
                if (notification == null) {
                    continue;
                }
                taken = notification;
                if (suspended) {
                    onFailure.accept(notification, new IllegalStateException("Запросы приостановлены до перезапуска после ошибки оплаты/доступа"));
                    notifyDone(notification);
                    continue;
                }
                process(notification);
                retries = 0;
                notifyDone(notification);
                onIdle.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stopped.countDown();
                return;
            } catch (RuntimeException e) {
                if (Fatal.check(e)) {
                    return;
                }
                if (retryLater(taken, e)) {
                    continue;
                }
                log.error("Не удалось обработать уведомление", e);
                if (taken != null) {
                    try { onFailure.accept(taken, e); }
                    catch (RuntimeException saveFailure) { log.error("Не удалось сохранить необработанное уведомление", saveFailure); }
                    notifyDone(taken);
                }
                active = null;
                retries = 0;
                if (e instanceof ru.rainedev.raine.llm.LlmFailure failure && failure.requiresOperator()) {
                    suspended = true;
                    log.error("Запросы приостановлены: проверьте баланс/доступ и перезапустите бота");
                }
                recoverFrom(e);
            }
        }
    }

    /** Обработка одного уведомления целиком — до wait/pause. */
    public void process(Notification notification) {
        // Detect chat change and reset context if needed
        long newChatId = extractChatId(notification);
        if (newChatId != 0 && newChatId != currentChatId) {
            log.debug("Chat changed from {} to {} — clearing context", currentChatId, newChatId);
            currentChatId = newChatId;
            context.clear();
        }

        boolean resuming = active != null && active.notification == notification;
        if (!resuming) active = new Turn(notification, transportGeneration.getAsLong());
        Turn turn = active;
        long generation = turn.generation;
        if (!available() || generation != transportGeneration.getAsLong()) { abortTransport(turn); return; }
        bindGeneration.accept(generation);
        long startedAt = turn.startedAt;
        long spentTokens = turn.tokens;
        if (!resuming) {
            // «тебе написали» открытый чат заменяет собой: в нём и так всё видно.
            // А вот «снимок готов, вот имя файла» в переписке не написано нигде —
            // выбросив этот текст, мы оставили бы её ждать того, что уже случилось
            String opened = openImmediately(notification).orElse("");
            String text;
            if (opened.isEmpty()) {
                text = notification.text();
            } else if (notification.ownText()) {
                text = notification.text() + "\n\n" + opened;
            } else {
                text = opened;
            }
            // локальное время сервера, а не UTC: модель путается, когда видит
            // 15:43 UTC и думает, что сейчас 15:43 по её часам, а на сервере 18:43
            java.time.ZonedDateTime localNow = java.time.ZonedDateTime.now();
            String localTime = localNow.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    + " " + localNow.getZone().getId();
            context.add(Message.user(text + "\nCurrent local time: " + localTime));

        }
        boolean recallMemories = turn.recall;
        boolean askedThisTurn = turn.asked;
        for (int step = turn.step; step < MAX_STEPS_PER_NOTIFICATION; step++) {
            turn.step = step;
            if (!available.getAsBoolean() || generation != transportGeneration.getAsLong()) {
                abortTransport(turn);
                return;
            }
            if (!turn.prepared && recallMemories) {
                recallIntoContext();
            }
            // набор пересобирается каждый шаг: инструменты появляются по ходу дела.
            // открытие чата добавляет отправку — со снимком, снятым заранее, её бы не было
            Toolbox tools = withFinishingTools(notification.tools());
            if (!turn.prepared && !askedThisTurn && tools.names().contains("ask")) {
                // напоминание встаёт прямо перед выбором действия, чтобы модель его заметила
                appendToLast(ASK_REMINDER);
            }
            turn.prepared = true;
            ChatResponse response = llm.chat(systemPrompt.text(), context(), tools.asJson());
            turn.prepared = false;
            turn.step = step + 1;
            spentTokens += response.totalTokens();
            turn.tokens = spentTokens;
            List<ToolCall> calls = response.toolCalls();

            // мысли и намерения — то, ради чего вообще стоит смотреть в лог
            if (response.text() != null && !response.text().isBlank()) {
                log.info("Мысли: {}", response.text().strip());
            }
            if (!calls.isEmpty()) {
                log.info("Действия: {}", calls.stream().map(ToolCall::name).toList());
            }

            if (calls.isEmpty()) {
                // ответ текстом действием не является — напоминаем и пробуем снова
                log.warn("Модель не вызвала ни одного инструмента, шаг {}", step);
                context.add(Message.user(BE_TOOL_CENTRIC));
                continue;
            }

            List<Message> results;
            try {
                // инструменты открытого чата остаются до конца хода, а не до конца
                // шага: иначе на следующем шаге вернутся инструменты прежнего чата,
                // и реакция или сообщение уйдут не туда
                results = tools.invoke(calls, appeared -> {
                    tools.add(appeared);
                    notification.tools().add(appeared);
                }, () -> available.getAsBoolean() && generation == transportGeneration.getAsLong());
            } catch (Toolbox.PartialFailure e) {
                // Earlier actions are real: retain the complete tool protocol and do not replay them.
                context.add(response.firstMessage().orElseThrow());
                context.addAll(e.results());
                appendToLast("Earlier successful actions have already happened. Do not repeat them. " + e.getMessage());
                turn.recall = false;
                recallMemories = false;
                if (response.totalTokens() >= contextTokenLimit) {
                    onContextOverflow.run();
                    active = null;
                    return;
                }
                continue;
            } catch (LowQualityException e) {
                // откат: сообщение модели в контекст не попадает, вместо него — подсказка
                log.info("Ответ низкого качества, пробуем иначе: {}", e.getMessage());
                context.add(Message.user(e.getMessage()));
                if (response.totalTokens() > contextTokenLimit) {
                    log.warn("Контекст переполнен, а подходящий ответ не найден — сбрасываем");
                    onContextOverflow.run();
                    active = null;
                    return;
                }
                continue;
            }

            context.add(response.firstMessage().orElseThrow());
            context.addAll(results);
            if (!available.getAsBoolean() || generation != transportGeneration.getAsLong()) {
                abortTransport(turn);
                return;
            }

            // после отправки сообщения за воспоминаниями не ходим: подмешанные между
            // репликами, они сбивают мысль, и вместо связного ответа выходит поток
            // случайных фактов
            recallMemories = calls.stream().noneMatch(call -> "send_telegram_message".equals(call.name()));
            askedThisTurn |= calls.stream().anyMatch(call -> "ask".equals(call.name()));
            turn.asked = askedThisTurn;
            turn.recall = recallMemories;
            if (!askedThisTurn && !recallMemories) {
                appendToLast(ASK_MISSED);
            }

            if (calls.stream().anyMatch(call -> Toolbox.FINISHING.contains(call.name()))) {
                reportCost(startedAt, spentTokens, step + 1);
                if (response.totalTokens() >= contextTokenLimit) {
                    onContextOverflow.run();
                }
                active = null;
                return;
            }

            if (!tools.isEmpty()) {
                appendToLast("\nWhat's your next action? Use a `tool` to act. Use #ask to consult with your "
                        + "knowledge database. The following tools available: " + String.join(", ", tools.names()));
            }
        }

        reportCost(startedAt, spentTokens, MAX_STEPS_PER_NOTIFICATION);
        log.warn("Достигнут предел шагов — прекращаем ход принудительно");
        active = null;
    }

    /**
     * Во что обошёлся один разговор. Без этой строки понять, сколько стоит
     * ответить человеку, можно только по счёту у поставщика в конце месяца.
     */
    private void reportCost(long startedAt, long tokens, int steps) {
        log.info("Ход закончен: {} c, шагов {}, израсходовано {} токенов",
                (System.currentTimeMillis() - startedAt) / 1000, steps, tokens);
        // Record token usage for dashboard
        try {
            String model = llm.modelName();
            if (model != null && !model.isBlank()) {
                WebUi.STATS.recordTokens(model, (int) Math.min(tokens, Integer.MAX_VALUE));
            }
        } catch (RuntimeException ignored) {
            // stats recording should never break the main loop
        }
    }

    private void notifyDone(Notification notification) {
        for (var listener : onNotificationDone) {
            try {
                listener.accept(notification);
            } catch (RuntimeException e) {
                // уборка после хода не должна ронять цикл
                log.warn("Не удалось завершить ход: {}", e.getMessage());
            }
        }
    }

    private void appendToLast(String addition) {
        Message last = context.getLast();
        context.set(context.size() - 1, last.withContent(last.content() + "\n" + addition));
    }

    /**
     * Если уведомление предлагает единственное действие «открыть» без параметров,
     * выполняем его сразу. Модель открывает чат в ста случаях из ста, и спрашивать
     * об этом — лишнее обращение к ней на каждое сообщение.
     */
    private java.util.Optional<String> openImmediately(Notification notification) {
        var tools = notification.tools().all();
        if (tools.size() != 1) {
            return java.util.Optional.empty();
        }
        Tool only = tools.iterator().next();
        if (!"open".equals(only.name()) || !only.parameters().path("properties").isEmpty()) {
            return java.util.Optional.empty();
        }
        try {
            log.debug("Открываю чат сразу, не спрашивая модель");
            // инструменты, которые откроет чат, попадают в набор уведомления —
            // вместе с ним они и исчезнут
            return java.util.Optional.of(
                    only.handler().call(only.parameters().objectNode(), notification.tools()::add));
        } catch (RuntimeException e) {
            log.warn("Не удалось открыть чат сразу: {}", e.getMessage());
            return java.util.Optional.empty();
        }
    }

    /**
     * Извлекает chat_id из текста уведомления.
     * Уведомления имеют формат: <notification chat_id="123">...内容...</notification>
     */
    private long extractChatId(Notification notification) {
        String text = notification.text();
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int start = text.indexOf("chat_id=\"");
        if (start < 0) {
            return 0;
        }
        start += 9; // длина "chat_id=\""
        int end = text.indexOf("\"", start);
        if (end < 0) {
            return 0;
        }
        try {
            return Long.parseLong(text.substring(start, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Воспоминания встают перед последним сообщением — как будто вспомнилось до ответа. */
    private void recallIntoContext() {
        String recalled = memory.recall(context);
        if (recalled.isEmpty()) {
            return;
        }
        Message last = context.getLast();
        context.set(context.size() - 1, last.withContent(recalled + last.content()));
    }

    /** wait и pause доступны всегда: иначе ход невозможно закончить. */
    private Toolbox withFinishingTools(Toolbox notificationTools) {
        Toolbox tools = new Toolbox();
        alwaysAvailable.all().forEach(tools::add);
        notificationTools.all().forEach(tools::add);
        tools.add(Tool.simple("wait", "Wait until further notifications", arguments -> "Success"));
        tools.add(Tool.simple("pause", "Pauses the conversation", arguments -> "Success"));
        return tools;
    }

    /** Повреждённый JSON означает испорченный контекст — его проще выбросить целиком. */
    /**
     * Пропавшая сеть не должна стоить ей сообщения.
     * <p>
     * Клиент уже пробовал трижды подряд; если сеть так и не вернулась, дальше
     * долбиться незачем — уведомление кладётся обратно в начало очереди и ждёт.
     * Контекст и шаг сохраняются: повтор продолжает тот же ход, не открывает
     * уведомление заново и не забывает уже выполненные действия.
     *
     * @return взяли ли уведомление на новую попытку
     */
    private boolean retryLater(Notification notification, RuntimeException failure) {
        boolean transientFailure = failure instanceof UncheckedIOException
                || failure instanceof ru.rainedev.raine.llm.LlmFailure endpoint && endpoint.retryable();
        if (notification == null || !transientFailure || stopping) {
            return false;
        }
        if (++retries > MAX_RETRIES) {
            log.error("Сеть не вернулась за {} попыток — уведомление отложено насовсем", MAX_RETRIES);
            retries = 0;
            return false;
        }
        // Resume the existing turn and context: previous tool actions cannot be rolled back.
        retrying = notification;
        log.warn("Сеть недоступна, вернусь к уведомлению через {} с (попытка {} из {})",
                retryPause.toSeconds(), retries, MAX_RETRIES);
        sleep(retryPause);
        return true;
    }

    private static void sleep(Duration pause) {
        try {
            Thread.sleep(pause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void recoverFrom(RuntimeException e) {
        String message = String.valueOf(e.getMessage()).toLowerCase();
        if (message.contains("json")) {
            log.warn("Контекст повреждён, сбрасываем");
            context.clear();
        }
    }
}
