package ru.rainedev.raine.core;

import it.tdlight.jni.TdApi;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Подбирает живые фразы из того, что пишут люди, — чтобы учиться манере
 * общения не из застывших правил, а из настоящей речи: из переписки
 * собеседников и из комментариев, которые она читает.
 * <p>
 * Сохраняет фразы с междометиями, смехом, сленгом, капсом, подколами,
 * оскорблениями и нежностями — всеми признаками живой речи и настоящих
 * отношений. Модель получает случайную выборку перед каждым ответом и
 * перенимает интонации, шутки, словечки, манеру подколов и то, как люди
 * друг к другу относятся.
 * <p>
 * Свои реплики в коллекцию не попадают: учиться надо у живых, а не у себя.
 */
public final class PhraseCollector {

    private static final Logger log = LoggerFactory.getLogger(PhraseCollector.class);

    /**
     * Сколько фраз хранить. Больше — коллекция медленнее читается и сильнее
     * шумит в промпте. Меньше — быстро вымывается и не отражает разнообразие.
     */
    private static final int MAX_PHRASES = 400;

    /** Сколько фраз подмешивать в промпт: больше — модель начинает повторять. */
    private static final int INJECT_COUNT = 8;

    /** Короче — не фраза, длиннее — уже простыня, так не говорят. */
    private static final int MIN_PHRASE = 2;
    private static final int MAX_PHRASE = 200;

    private final Path file;
    private final long myId;

    private final Deque<String> phrases = new ArrayDeque<>();

    public PhraseCollector(Path file, long myId) {
        this.file = file;
        this.myId = myId;
        load();
    }

    /** Разбирает входящее сообщение и, если фраза живая, кладёт в коллекцию. */
    public void observe(TdApi.Message message) {
        if (message.isOutgoing) {
            return;
        }
        if (message.senderId instanceof TdApi.MessageSenderUser user && user.userId == myId) {
            return;
        }
        if (!(message.content instanceof TdApi.MessageText text)) {
            return;
        }
        observeText(text.text.text);
    }

    /**
     * Кладёт в коллекцию любую живую фразу, откуда бы она ни пришла: чужой
     * комментарий под роликом, пост в чужом канале, чья-то подпись. Учиться
     * манере можно не только у собеседников — подростки в комментариях пишут
     * ровно так же, как в личке, только ещё свободнее.
     * <p>
     * Свои тексты сюда не скармливают: учиться надо у живых людей, а не у себя.
     */
    public void observeText(String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        String phrase = raw.strip();
        if (phrase.length() < MIN_PHRASE || phrase.length() > MAX_PHRASE) {
            return;
        }
        if (!isAlive(phrase)) {
            return;
        }
        synchronized (phrases) {
            phrases.addLast(phrase);
            while (phrases.size() > MAX_PHRASES) {
                phrases.removeFirst();
            }
        }
        persist();
    }

    /**
     * Случайная выборка для подмешивания в промпт. Пустая строка, если
     * коллекция пуста или нечего показать.
     */
    public String sample() {
        String[] snapshot;
        synchronized (phrases) {
            if (phrases.isEmpty()) {
                return "";
            }
            snapshot = phrases.toArray(new String[0]);
        }
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        StringBuilder out = new StringBuilder();
        int count = Math.min(INJECT_COUNT, snapshot.length);
        java.util.Set<Integer> picked = new java.util.HashSet<>();
        while (picked.size() < count && picked.size() < snapshot.length) {
            picked.add(rnd.nextInt(snapshot.length));
        }
        for (int idx : picked) {
            out.append("- ").append(snapshot[idx]).append('\n');
        }
        return out.toString();
    }

    /**
     * Признаки живой речи: смех, капс, междометия, сленг, эмоциональные
     * хвосты. Обычные нейтральные фразы в коллекцию не попадают — им
     * учиться нечему.
     */
    private static boolean isAlive(String phrase) {
        String low = phrase.toLowerCase();
        // смех
        if (low.contains("ахах") || low.contains("хаха") || low.contains("лол")
                || low.contains("ору") || low.contains("кек") || low.contains("рофл")
                || low.contains("xd") || low.contains("ыыы")) {
            return true;
        }
        // капс — хотя бы треть заглавных и длина больше 3
        int upper = 0;
        for (int i = 0; i < phrase.length(); i++) {
            if (Character.isUpperCase(phrase.charAt(i))) {
                upper++;
            }
        }
        if (phrase.length() > 3 && upper * 3 > phrase.length()) {
            return true;
        }
        // междометия
        if (low.startsWith("блин") || low.startsWith("бля") || low.startsWith("пизд")
                || low.startsWith("охуеть") || low.startsWith("капец") || low.startsWith("ебать")
                || low.startsWith("ой") || low.startsWith("ну ") || low.startsWith("чё")
                || low.startsWith("хз") || low.startsWith("пон") || low.startsWith("ага")) {
            return true;
        }
        // сленг
        if (low.contains("кринж") || low.contains("вайб") || low.contains("имб")
                || low.contains("база") || low.contains("защквар") || low.contains("жиза")
                || low.contains("слэй") || low.contains("мог") || low.contains("сю-ю")
                || low.contains("аура") || low.contains("тильт") || low.contains("риза")) {
            return true;
        }
        // эмоциональные многоточия и восклицания
        if (phrase.contains("!!!") || phrase.contains("???") || phrase.contains("…")
                || phrase.matches(".*[а-яА-Я]\\1{2,}.*")) {   // растянутые буквы
            return true;
        }
        // перепалки, подколы, оскорбления (по-дружески и всерьёз), конфликты
        // и нежности — она учится тому, как люди реально разговаривают друг
        // с другом: как подкалывают, как далеко заходят, кто с кем близок
        if (low.contains("дурак") || low.contains("даун") || low.contains("дебил")
                || low.contains("идиот") || low.contains("придурок") || low.contains("кончен")
                || low.contains("тупой") || low.contains("тупая") || low.contains("еблан")
                || low.contains("ебень") || low.contains("лох") || low.contains("чмо")
                || low.contains("отброс") || low.contains("заебал") || low.contains("отвали")
                || low.contains("пошёл ты") || low.contains("пошел ты") || low.contains("сам ты")
                || low.contains("ненавижу") || low.contains("бесишь") || low.contains("достал")
                || low.contains("позор") || low.contains("обожаю") || low.contains("люблю тебя")
                || low.contains("скучаю") || low.contains("солныш") || low.contains("зая")
                || low.contains("милый") || low.contains("милая") || low.contains("красав")
                || low.contains("лучший") || low.contains("лучшая")) {
            return true;
        }
        return false;
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String phrase = line.strip();
                if (!phrase.isEmpty()) {
                    synchronized (phrases) {
                        phrases.addLast(phrase);
                        while (phrases.size() > MAX_PHRASES) {
                            phrases.removeFirst();
                        }
                    }
                }
            }
            log.info("Загружено фраз из {}: {}", file, phrases.size());
        } catch (IOException e) {
            log.warn("Не удалось прочитать {}: {}", file, e.getMessage());
        }
    }

    private void persist() {
        try {
            Files.createDirectories(file.getParent());
            StringBuilder out = new StringBuilder();
            synchronized (phrases) {
                for (String phrase : phrases) {
                    out.append(phrase).append('\n');
                }
            }
            Files.write(file, out.toString().getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось сохранить фразы", e);
        }
    }
}
