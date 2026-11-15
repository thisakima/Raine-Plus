package ru.rainedev.raine.image;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.llm.LlmClient;
import ru.rainedev.raine.llm.Message;
import ru.rainedev.raine.prompt.Prompts;

/**
 * Делает снимок по пожеланию: превращает его в задание для рисующей модели,
 * получает картинку и сам решает, годится ли она.
 * <p>
 * Проверка нужна потому, что рисующие модели регулярно выдают лишние пальцы
 * и сломанные позы. Отправить такое — хуже, чем не отправить ничего.
 */
public final class ImageGenerator {

    private static final Logger log = LoggerFactory.getLogger(ImageGenerator.class);

    /**
     * Промпт-инженер лежит в файлах рядом с остальными промптами: его правят
     * без пересборки, как и всё остальное.
     * <p>
     * Два варианта: для чекпойнтов Stable Diffusion — теги с весами, для
     * grok-imagine и прочих шлюзов — связное описание кадра. Разница в
     * качестве заметная: развёрнутое описание со светом, оптикой и цветом
     * выходит сильно ближе к тому, что выдаёт сама ChatGPT.
     */
    private static final String ENGINEER_NATURAL = "prompt_engineer.md";
    private static final String ENGINEER_KEYWORDS = "prompt_engineer_sd.md";

    /**
     * Теги для рисующей модели лежат отдельно от описания персонажа: часть
     * шлюзов отклоняет запрос, если в нём встретились слова из negative
     * промпта. В чат-модель эти строки вообще не попадают.
     */
    private static final String APPEARANCE_SD = "character_appearance_sd.md";

    /**
     * Единый источник правды по стилю: как она выглядит и как выглядит
     * кадр. Читается инженером целиком, а раздел «Style anchor» из него
     * дословно дописывается в финальный промпт рисующей модели — иначе
     * стиль плывёт от снимка к снимку.
     */
    private static final String STYLE_FILE = "photo_style.md";
    private static final String STYLE_ANCHOR_HEADER = "# Style anchor";

    /**
     * Сколько слов допустимо в задании. У чекпойнта длинный промпт размывает
     * облик, у шлюзов наоборот — детали работают в плюс.
     */
    private static final int MAX_KEYWORD_WORDS = 80;
    private static final int MAX_NATURAL_WORDS = 240;

    private static final String QUALITY_GATE = """
            You are an image quality gate.

            You will be shown an image generated from a description and a character appearance.
            Decide whether it is good enough to send to a real person as a photo.
            Judge it the way a person glancing at a photo would: obvious defects matter, subtle ones do not.
            Each rejection costs another expensive generation, so reject only for real problems.

            Reject the image if ANY of these is true:
            - a body part is clearly malformed, missing, duplicated, fused or impossibly placed (six fingers,
              two heads, an arm from the wrong place). Mild foreshortening or a hand partly out of frame is NOT
              a defect;
            - the character is clearly someone else: hair colour or length, eye colour, glasses or age visibly
              disagree with the description;
            - the scene is nonsensical: floating in mid-air, melting objects, duplicated people;
            - the image is badly degraded: heavy blur over the subject, garbled textures, obvious artifacts;
            - the image ignores the requested subject entirely.

            Important:
            - Judge only technical quality, identity and anatomy. How much skin the outfit shows
              is not your concern here and must never be a reason to reject.
            - An awkward selfie arm, background clutter, imperfect light or slightly odd framing are ACCEPTABLE.
              Real photos are not perfect.
            - If you are unsure about something, treat it as acceptable.
            - The character description is the baseline, but the specific wish overrides it where they disagree.
            - The character must read as an adult.

            Answer with exactly two lines:
            VERDICT: OK or REJECT
            FEEDBACK: <what to change, one sentence>""";

    /**
     * Слова, которые промпт-инженер отказывается обрабатывать. Их вырезают из
     * пожелания перед обращением к нему и дописывают в готовый промпт после.
     */
    private static final List<String> BLUNT_WORDS = List.of(
            "explicit nudity", "explicit nude", "explicit erotic", "nsfw", "nude", "topless");

    /** Не убирается ни при каких настройках. */
    private static final String HARD_NEGATIVE = "child, kid, teen, loli, shota";

    /**
     * Тот же запрет, но без терминов, на которые спотыкаются фильтры
     * контент-шлюзов: у аниме-чекпойнтов эти слова обязательны, а шлюз
     * вместо картинки отвечает отказом.
     */
    private static final String HARD_NEGATIVE_SAFE = "child, kid, teenager, young";

    /** Столько раз просим модель укоротить задание, прежде чем обрезать самим. */
    private static final int SHORTEN_ATTEMPTS = 2;

    private final ImageClient horde;
    private final LlmClient llm;
    private final Prompts prompts;
    private final Path gallery;
    private final String characterName;
    private final String visionModel;
    private final int maxTrials;
    private final Random random = new Random();

    /**
     * Съёмка идёт одна за раз. Очередь бывает занята десятками минут, и без
     * этого запрета она, не дождавшись, запускает вторую работу поверх первой:
     * оба задания стоят очков, а нужен один снимок.
     */
    private final java.util.concurrent.atomic.AtomicBoolean busy =
            new java.util.concurrent.atomic.AtomicBoolean();

    public ImageGenerator(ImageClient horde, LlmClient llm, Prompts prompts, Path gallery,
                          String characterName, String visionModel, int maxTrials) {
        this.horde = horde;
        this.llm = llm;
        this.prompts = prompts;
        this.gallery = gallery;
        this.characterName = characterName;
        this.visionModel = visionModel;
        this.maxTrials = Math.max(1, maxTrials);
    }

    public boolean isAvailable() {
        return horde.isAvailable();
    }

    private record Prompt(String positive, String negative) {}

    public boolean isBusy() {
        return busy.get();
    }

    /**
     * Снимает в фоне. Ожидание в очереди доходит до часа, и держать ради этого
     * весь разговор нельзя: пока рисуется, она должна жить обычной жизнью.
     *
     * @param onReady   вызовется с готовым файлом
     * @param onFailure вызовется с причиной, если не вышло
     * @return false, если съёмка уже идёт
     */
    public boolean takeInBackground(String wish, java.util.function.Consumer<Path> onReady,
                                    java.util.function.Consumer<String> onFailure) {
        if (!busy.compareAndSet(false, true)) {
            return false;
        }
        Thread.ofVirtual().name("raine-camera").start(() -> {
            try {
                onReady.accept(take(wish));
            } catch (RuntimeException e) {
                log.warn("Снимок не получился: {}", e.getMessage());
                onFailure.accept(e.getMessage());
            } finally {
                busy.set(false);
            }
        });
        return true;
    }

    /** @return файл в галерее */
    public Path take(String wish) {
        String appearance = prompts.load("character_appearance.md");
        if (keywordStyle()) {
            // теги для чекпойнта дописываем к описанию: сам он их не знает
            appearance = appearance + "\n\n" + prompts.load(APPEARANCE_SD);
        }
        boolean explicit = isExplicit(wish);
        Prompt prompt = engineer(wish, appearance, "", null);
        String feedbackSoFar = "";
        String firstFeedback = "";

        // изредка задание переписывается с нуля: если правки завели не туда,
        // выбраться из этого места удобнее заново
        int restartEvery = Math.max(2, (int) Math.round(Math.sqrt(maxTrials)));

        for (int trial = 1; trial <= maxTrials; trial++) {
            log.info("Снимок, попытка {}: {}", trial, prompt.positive());
            byte[] image;
            try {
                image = horde.generate(prompt.positive(), prompt.negative(),
                        768 + random.nextInt(257), 768 + random.nextInt(257),
                        1.0 + random.nextDouble() * 4.0, 30);
            } catch (RuntimeException e) {
                log.warn("Снимок не получился: {}", e.getMessage());
                if (trial == maxTrials) {
                    throw e;
                }
                continue;
            }

            // откровенный снимок проверять нечем: та же модель зрения, которой
            // мы показываем результат, такое смотреть отказывается — выйдет
            // не проверка, а сожжённые попытки. Такие снимки принимаем как есть
            if (explicit) {
                log.info("Снимок откровенный — принимаю без проверки");
                return save(image);
            }
            String verdict = assess(image, wish, appearance);
            if (verdict.isEmpty()) {
                return save(image);
            }
            log.info("Снимок забракован: {}", verdict);
            if (firstFeedback.isEmpty()) {
                // в сообщении о неудаче приводится первый отзыв: он ближе
                // к исходному пожеланию и понятнее говорит, что не так
                firstFeedback = verdict;
            }
            feedbackSoFar = verdict;
            prompt = trial % restartEvery == 0
                    ? engineer(wish, appearance, "", null)
                    : engineer(wish, appearance, feedbackSoFar, prompt);
        }
        throw new IllegalStateException("Не вышло сделать удачный снимок: " + firstFeedback
                + ". Попробуй описать снимок короче.");
    }

    /** Ответы, которыми модель отказывается смотреть, а не оценивает. */
    private static final List<String> REFUSALS = List.of(
            "i'm sorry", "i am sorry", "can't assist", "cannot assist", "can't help", "cannot help",
            "unable to assist", "i can't provide", "i cannot provide", "не могу помочь",
            "не могу выполнить", "не могу описать");

    static boolean isRefusal(String verdict) {
        String lower = verdict == null ? "" : verdict.toLowerCase();
        return REFUSALS.stream().anyMatch(lower::contains);
    }

    /** Откровенное задание видно по прямым словам — тем самым, что мы вычищаем из промпта. */
    static boolean isExplicit(String wish) {
        String lower = wish == null ? "" : wish.toLowerCase();
        return BLUNT_WORDS.stream().anyMatch(lower::contains);
    }

    /** Ключевые слова с весами — синтаксис чекпойнтов. Для шлюзов не годится. */
    private static final String BASE_NEGATIVE = "(text:2), (signature:2), watermark, deformed hands, extra fingers";

    /** Естественный текст — та же мысль, но без скобочек и весов. */
    private static final String BASE_NEGATIVE_NATURAL =
            "3D render, photorealistic, photograph, CGI, western comic style, oil painting, "
                    + "text, signature, watermark, deformed hands, extra fingers";

    /**
     * Хвост стиля, который дословно уходит в рисующую модель. Берётся из
     * файла стиля, чтобы правка внешнего вида не требовала пересборки.
     */
    private String styleAnchor() {
        try {
            String style = prompts.load(STYLE_FILE);
            int at = style.indexOf(STYLE_ANCHOR_HEADER);
            if (at < 0) {
                return "";
            }
            return style.substring(at + STYLE_ANCHOR_HEADER.length()).strip();
        } catch (RuntimeException e) {
            log.warn("Не удалось загрузить стиль снимков: {}", e.getMessage());
            return "";
        }
    }

    /** Полный файл стиля — инженеру, чтобы он не выдумывал свой. */
    private String styleSpec() {
        try {
            return prompts.load(STYLE_FILE);
        } catch (RuntimeException e) {
            return "";
        }
    }

    /**
     * Какой стиль промпта понимает модель: теги с весами или связный текст.
     * Резкость, с которой это надо учитывать, большая: шлюз на теги отвечает
     * «masterpiece, 8k»-магией, которая для него ничего не значит.
     */
    private boolean keywordStyle() {
        return horde.wantsKeywordPrompt();
    }

    private int maxPromptWords() {
        return keywordStyle() ? MAX_KEYWORD_WORDS : MAX_NATURAL_WORDS;
    }

    /** Промпт-инженер, загруженный с диска — правится без пересборки. */
    private String engineerPrompt() {
        try {
            return prompts.load(keywordStyle() ? ENGINEER_KEYWORDS : ENGINEER_NATURAL);
        } catch (RuntimeException e) {
            // файл мог не долететь при обновлении — берём встроенный минимум,
            // чтобы съёмка работала всегда
            log.warn("Не удалось загрузить промпт-инженера, беру встроенный: {}", e.getMessage());
            return keywordStyle()
                    ? "You are a Stable Diffusion prompt engineer. Output two lines:\nPOSITIVE: <tags>\nNEGATIVE: <tags>"
                    : "You are an art director for 2D anime illustration. Describe the scene in the "
                            + "given art style. Two lines:\n"
                            + "POSITIVE: <description>\nNEGATIVE: <what must not appear>";
        }
    }

    private Prompt engineer(String wish, String appearance, String feedback, Prompt previous) {
        // прямые слова промпт-инженер обрабатывать откажется — уберём их,
        // а в готовый промпт добавим сами
        String toned = wish;
        for (String word : BLUNT_WORDS) {
            toned = toned.replaceAll("(?i)" + java.util.regex.Pattern.quote(word), "");
        }

        boolean keywords = keywordStyle();
        int maxWords = maxPromptWords();

        StringBuilder request = new StringBuilder("<character name=\"%s\">\n%s\n</character>\n\n"
                .formatted(characterName, appearance));
        String style = styleSpec();
        if (!style.isBlank()) {
            request.append("<art_style>\n").append(style).append("\n</art_style>\n\n");
        }
        // шлюзы (grok-imagine, DALL-E) понимают только связный текст — указываем
        // режим явно, чтобы модель писала описание кадра, а не теги
        request.append(keywords
                ? "Write a Stable Diffusion prompt for the scene above, in the art style given.\n\n"
                : "Write a description of this scene as a 2D anime illustration, following the art "
                        + "style given above exactly. It must read like an art director's shot "
                        + "description, not like a tag dump.\n\n");
        request.append("Description: ").append(toned);
        if (previous != null) {
            // правим прежнее задание, а не сочиняем заново: удачные находки сохраняются
            request.append("\n\nPrevious prompt:\nPOSITIVE: ").append(previous.positive())
                    .append("\nNEGATIVE: ").append(previous.negative());
        }
        if (!feedback.isBlank()) {
            request.append("\n\nPrevious attempt was rejected: ").append(feedback);
        }

        List<Message> conversation = new java.util.ArrayList<>(List.of(Message.user(request.toString())));
        String answer = ask(conversation, toned, appearance);

        String positive = line(answer, "POSITIVE:", toned);
        String negative = line(answer, "NEGATIVE:", "");

        // слишком длинное задание не обрезаем на полуслове, а просим переписать:
        // машинная обрезка отсекает половину смысла и оставляет висящую скобку
        for (int attempt = 1; attempt <= SHORTEN_ATTEMPTS && !fitsInWords(positive, negative, maxWords); attempt++) {
            log.info("Задание длинновато, прошу укоротить (попытка {})", attempt);
            conversation.add(Message.assistant(answer));
            conversation.add(Message.user(keywords
                    ? "The prompt is too long. Shorten it to %d words or less; keep the weights syntax."
                            .formatted(maxWords)
                    : "The description is too long. Shorten it to %d words or less, keeping the light, "
                                    + "camera, colour grade and the scene as specific as they were. "
                                    + "Cut filler, not detail."
                            .formatted(maxWords)));
            answer = ask(conversation, toned, appearance);
            positive = line(answer, "POSITIVE:", positive);
            negative = line(answer, "NEGATIVE:", negative);
        }
        positive = tidy(positive, maxWords);
        negative = tidy(negative, maxWords);

        for (String word : BLUNT_WORDS) {
            if (wish.toLowerCase().contains(word)) {
                positive += ", " + word;
            }
        }
        // якорь стиля дописывается дословно и последним: без него модель
        // каждый раз решает заново, рисовать аниме или фото, и серия
        // снимков рассыпается на разные стили
        String anchor = styleAnchor();
        if (!anchor.isBlank()) {
            positive = positive.strip() + ", " + anchor;
        }
        String base = keywords ? BASE_NEGATIVE : BASE_NEGATIVE_NATURAL;
        String hard = keywords ? HARD_NEGATIVE : HARD_NEGATIVE_SAFE;
        return new Prompt(positive, join(base, negative, hard));
    }

    /** Сколько раз просить шлюз заново, если он отклонил запрос фильтром содержимого. */
    private static final int FILTER_RETRIES = 2;

    /**
     * Просит модель составить задание.
     * <p>
     * Шлюзы с фильтром содержимого отклоняют запросы недетерминированно: один
     * и тот же текст то проходит, то нет. Раньше отказ означал тихий провал —
     * в рисующую модель уходило сырое пожелание в одну строку, и снимок
     * выходил совсем не того стиля. Поэтому на отказ пробуем ещё раз,
     * с урезанным запросом: меньше текста — меньше поверхность для фильтра.
     */
    private String ask(List<Message> conversation, String wish, String appearance) {
        for (int attempt = 1; attempt <= FILTER_RETRIES; attempt++) {
            try {
                String answer = llm.chat(engineerPrompt().formatted(maxPromptWords()), conversation, null).text();
                if (answer == null) {
                    return "";
                }
                if (!isRefusal(answer)) {
                    return answer;
                }
                log.warn("Шлюз отклонил запрос промпт-инженера (фильтр), попытка {} из {}",
                        attempt, FILTER_RETRIES);
                if (attempt < FILTER_RETRIES) {
                    // пробуем снова с минимальным запросом: только суть сцены
                    // и короткое описание внешности, без длинных пояснений
                    conversation = new java.util.ArrayList<>(
                            List.of(Message.user(minimalRequest(wish, appearance))));
                }
            } catch (RuntimeException e) {
                log.warn("Промпт не составился, беру пожелание как есть: {}", e.getMessage());
                return "";
            }
        }
        return "";
    }

    /**
     * Урезанный запрос на второй случай: короткое описание сцены и внешности,
     * без длинных правил. Фильтр содержимого срабатывает на объём и на
     * отдельные формулировки, поэтому меньше текста — больше шансов пройти.
     */
    private String minimalRequest(String wish, String appearance) {
        return "Describe this scene for an image model as an ordinary photograph, "
                + "with light, camera and colour grade:\n\n" + wish
                + "\n\nThe subject: " + shortIdentity(appearance);
    }

    /**
     * Короткая выжимка внешности: первые содержательные абзацы файла вместе
     * со списком неизменных черт, но без длинных разделов про стиль и съёмку.
     * <p>
     * Именно список черт здесь обязателен: без него модель на повторе
     * придумывает свой цвет волос и теряет очки, и снимок выходит про
     * другого человека.
     */
    private static String shortIdentity(String appearance) {
        StringBuilder out = new StringBuilder();
        for (String paragraph : appearance.split("\n\n")) {
            String text = paragraph.strip();
            if (text.isEmpty() || text.startsWith("#") || text.startsWith("---")) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(text.replaceAll("\\s+", " "));
            if (out.length() >= IDENTITY_LIMIT) {
                break;
            }
        }
        if (out.length() == 0) {
            return "a 19-year-old woman";
        }
        return out.length() > IDENTITY_LIMIT
                ? out.substring(0, IDENTITY_LIMIT) + "…"
                : out.toString();
    }

    /** Столько символов выжимки внешности достаточно, чтобы не потерять черты. */
    private static final int IDENTITY_LIMIT = 700;

    private static boolean fitsInWords(String positive, String negative, int maxWords) {
        return words(positive) <= maxWords && words(negative) <= maxWords;
    }

    private static int words(String prompt) {
        return prompt.isBlank() ? 0 : prompt.strip().split("\\s+").length;
    }

    /** @return пусто, если снимок годится, иначе — что не так */
    private String assess(byte[] image, String wish, String appearance) {
        try {
            String verdict = llm.describeImage(visionModel, QUALITY_GATE,
                    "<character>\n%s\n</character>\n<wanted>\n%s\n</wanted>".formatted(appearance, wish), image);
            if (verdict == null || verdict.isBlank()) {
                return "";   // проверяющий промолчал — не повод жечь ещё одну генерацию
            }
            if (isRefusal(verdict)) {
                // «я не могу это смотреть» — это отказ проверять, а не изъян снимка.
                // Принять его за брак значит выбросить готовую картинку и встать
                // в очередь заново, на второй час
                log.info("Проверяющий отказался смотреть — принимаю снимок как есть");
                return "";
            }
            return verdict.toUpperCase().contains("VERDICT: OK") ? "" : line(verdict, "FEEDBACK:", verdict.strip());
        } catch (RuntimeException e) {
            log.warn("Проверить снимок не вышло, принимаю как есть: {}", e.getMessage());
            return "";
        }
    }

    private Path save(byte[] image) {
        try {
            Files.createDirectories(gallery);
            Path file = gallery.resolve(System.currentTimeMillis() + ".webp");
            Files.write(file, image);
            log.info("Снимок сохранён: {}", file.getFileName());
            return file;
        } catch (IOException e) {
            throw new IllegalStateException("Снимок не сохранился", e);
        }
    }

    /**
     * Приводит задание в порядок: запятая после весовых групп и обрезка до
     * разумной длины. Длинные задания размывают облик персонажа — модель
     * начинает рисовать кого-то другого.
     */
    private static String tidy(String prompt, int maxWords) {
        String result = prompt.replace(") ", "), ");
        String[] words = result.split("\\s+");
        if (words.length > maxWords) {
            result = String.join(" ", java.util.Arrays.copyOf(words, maxWords));
        }
        return result;
    }

    private static String line(String text, String marker, String fallback) {
        if (text == null) {
            return fallback;
        }
        for (String line : text.lines().toList()) {
            if (line.strip().toUpperCase().startsWith(marker)) {
                String value = line.strip().substring(marker.length()).strip();
                if (!value.isEmpty()) {
                    return value;
                }
            }
        }
        return fallback;
    }

    private static String join(String... parts) {
        return String.join(", ", java.util.Arrays.stream(parts).filter(p -> p != null && !p.isBlank()).toList());
    }
}
