package ru.rainedev.raine.config;

import java.nio.file.Path;

/**
 * Настройки бота. Читаются из config.properties рядом с проектом,
 * любое значение перекрывается переменной окружения (RAINE_API_ID и т.д.).
 * <p>
 * Каждая настройка объявляется вместе с пояснением: недостающие дописываются
 * в файл при запуске, так что после обновления видно, что появилось нового.
 */
public record Config(
        int apiId,
        String apiHash,
        String phone,
        long ownerId,
        Path sessionDir,
        Path promptsDir,
        Path diaryDir,
        Path workingMemoryFile,
        int diaryInjectionMaxLength,
        double diaryPlagiarismThreshold,
        double diaryMinRelatedness,
        int diaryConsolidationBudget,
        Vision vision,
        Voice voice,
        Hearing hearing,
        Camera camera,
        String webSearchUrl,
        String webSearchKey,
        String webSearchProvider,
        Llm llm,
        Embedding embedding,
        Character character,
        long contextTokenLimit,
        String lockdown,
        boolean lockdownAllowChannels,
        boolean sleepConsolidation,
        Consolidation consolidation,
        Night night,
        Around around,
        int noticeDelaySeconds,
        int webUiPort,
        boolean offlineLife,
        boolean readOnly,
        boolean llmTranscript,
        Behaviour behaviour,
        Repeat repeat,
        Browser browser,
        TikTok tiktok
) {

    /** Параметры OpenAI-совместимого эндпоинта. */
    public record Consolidation(int minutes, int maxRequests, long maxTokens) {
        public Consolidation {
            if (minutes < 1 || maxRequests < 1 || maxTokens < 1)
                throw new IllegalArgumentException("Лимиты консолидации должны быть положительными");
        }
    }

    public record Llm(String baseUrl, String model, String apiKey, double temperature) {}

    /**
     * Векторы памяти. По умолчанию — эндпоинт модели, но можно указать
     * локальный: Ollama и любой OpenAI-совместимый сервер отдают /embeddings
     * тем же форматом.
     *
     * @param baseUrl адрес эмбеддингов; пусто — адрес модели
     * @param apiKey ключ эмбеддингов; пусто — без ключа; отсутствие — ключ модели
     * @param model название модели
     */
    public record Embedding(String baseUrl, String apiKey, String model) {}

    /**
     * Модели зрения. Дешёвая для потокового — стикеры и аватарки, основная
     * для того, что важно разглядеть.
     */
    /**
     * @param recentDepth у скольких последних сообщений разглядывать вложения:
     *                    разбор десятка картинок стоит минуты молчания, а разговор
     *                    идёт про последние из них
     */
    public record Vision(boolean enabled, String model, String cheapModel, Path cacheDir, int recentDepth,
                         boolean videoInChannels) {}

    /**
     * Ночной сон. Время выбирается заново каждую ночь в пределах окна:
     * подъём ровно в 7:00 каждый день — это будильник, а не живой человек.
     */
    public record Night(boolean enabled, java.time.LocalTime bedtimeFrom, java.time.LocalTime bedtimeTo,
                        java.time.LocalTime wakeFrom, java.time.LocalTime wakeTo) {}

    /** Знание об окружающем: время суток и погода. */
    public record Around(boolean enabled, double latitude, double longitude) {}

    /** Синтез речи для голосовых сообщений. */
    /**
     * @param provider чем синтезируется: openai (совместимый эндпоинт) или fish
     * @param format   в каком формате просим звук: pcm, wav, mp3 или opus
     * @param voice    голос: для openai — имя, для fish — reference_id
     */
    public record Voice(boolean enabled, String provider, String format, String baseUrl, String apiKey,
                        String model, String voice, String language, double speed, int sampleRate, int bitrate,
                        Path directory) {
        public Voice {
            if (!"openai".equalsIgnoreCase(provider) && !"fish".equalsIgnoreCase(provider)) {
                throw new IllegalArgumentException(
                        "Неизвестный синтез речи '" + provider + "' — бывает openai или fish");
            }
            if (!"pcm".equalsIgnoreCase(format) && !"wav".equalsIgnoreCase(format)
                    && !"mp3".equalsIgnoreCase(format) && !"opus".equalsIgnoreCase(format)) {
                throw new IllegalArgumentException(
                        "Неизвестный формат синтеза '" + format + "' — бывает pcm, wav, mp3 или opus");
            }
        }
    }

    /**
     * Слух: расшифровка звуковой дорожки видео. Голосовые и кружки
     * расшифровывает сам Telegram за премиум, а обычное видео — нет.
     */
    public record Hearing(boolean enabled, String baseUrl, String apiKey, String model, String language) {}

    /** Генерация снимков. */
    /**
     * @param queueMinutes сколько ждать своей очереди в сети добровольцев:
     *                     в загруженный час до картинки бывает три четверти часа
     */
    public record Camera(boolean enabled, String baseUrl, String apiKey, java.util.List<String> models,
                         Path gallery, int maxTrials, int queueMinutes) {}

    /** Значения, подставляемые в промпты вместо ${CHARACTER_NAME} и прочих. */
    public record Character(String name, String nickname, String papikName) {}

    /**
     * Повадки, которые хочется менять, не пересобирая проект.
     *
     * @param chatHistoryLength история чата ограничивается объёмом текста,
     *                          а не числом сообщений: тридцать постов из канала —
     *                          это перебор контекста, а тридцать «ок» — наоборот мало
     * @param wakeOnPinnedChat будят ли ночью сообщения из закреплённых чатов
     * @param dayNaps          отлучается ли она изредка среди дня
     * @param writeToNewPeople может ли писать первой тем, с кем переписки ещё не было
     * @param stickers         пользуется ли стикерами
     * @param toolReminder     как часто напоминать, что переписка — не только текст
     */
    public record Behaviour(int chatHistoryLength, boolean wakeOnPinnedChat, boolean dayNaps,
                            boolean writeToNewPeople, boolean stickers, double toolReminder) {}

    /**
     * Насколько строго ловить повторы за собой.
     *
     * @param triggerMax  совпадение с одним прошлым сообщением
     * @param triggerAvg  средняя похожесть на всё сказанное — хождение по кругу
     * @param maxHistory  сколько своих реплик держать для сравнения
     */
    public record Repeat(double triggerMax, double triggerAvg, int maxHistory) {}

    /**
     * Тело для браузера: живой Firefox под управлением Playwright. Профиль
     * постоянный — в нём остаются входы на сайты (в том числе в TikTok), так
     * что логиниться заново после перезапуска не нужно.
     *
     * @param headless    без окна. Для TikTok лучше false + виртуальный дисплей (Xvfb):
     *                    так она выглядит человечнее для антибот-систем
     * @param engine      chromium | firefox | webkit; Firefox по умолчанию — он
     *                    без приметок Chromium, которые первым делом видит антибот
     * @param userAgent   строка браузера; пусто — оставляет свою
     * @param loginBinary настоящий браузер для ручного входа; пусто — найти самому.
     *                    Вход идёт не тем Firefox, которым управляет Playwright:
     *                    это патченая nightly-сборка, и Google с TikTok не пускают
     *                    в аккаунт через неё («браузер может быть небезопасным»).
     */
    public record Browser(boolean enabled, boolean headless, String engine, Path profileDir, String locale,
                          String timezone, int viewportWidth, int viewportHeight, String userAgent,
                          String loginBinary) {

        /**
         * Каталог профиля под конкретный движок. Профили Chromium и Firefox
         * несовместимы: Firefox 137 не открывает профиль, созданный Firefox 159
         * (проверено), и наоборот — поэтому у каждого свой подкаталог.
         */
        public Path profileFor() {
            return profileDir.resolve(engineName());
        }

        /**
         * Профиль для ручного входа — отдельный и от её рабочего, и от личного
         * профиля человека: настоящий браузер другой версии, и общим профилем
         * им не пользоваться.
         */
        public Path loginProfile() {
            return profileDir.resolve("login");
        }

        public String engineName() {
            String value = engine == null ? "" : engine.strip().toLowerCase();
            return switch (value) {
                case "chromium", "chrome" -> "chromium";
                case "webkit" -> "webkit";
                default -> "firefox";
            };
        }
    }

    /**
     * Привычки в TikTok. Всё с оглядкой на антибот: действия редкие, с
     * суточными пределами и охлаждением, первые дни — только просмотр.
     * Автоматизация TikTok нарушает его правила и может привести к бану —
     * пределы намеренно низкие.
     *
     * @param maxVideosPerSession сколько роликов смотреть за один заход
     * @param cooldownMinutes     пауза между сессиями
     * @param warmupDays          сколько дней с момента первого входа только смотреть
     */
    public record TikTok(boolean enabled, String username, Path downloadDir, int maxVideosPerSession,
                         double likeChance, double commentChance, double repostChance,
                         int dailyLikeCap, int dailyCommentCap, int dailyFollowCap, int dailyDmCap,
                         int cooldownMinutes, int warmupDays) {}

    /** Пустое значение настройки означает «как по умолчанию». */
    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static final Path DEFAULT_FILE = Path.of("config.properties");

    public static Path defaultFile() {
        return DEFAULT_FILE;
    }

    public static Config load() {
        return load(DEFAULT_FILE);
    }

    public static Config load(Path file) {
        Settings s = Settings.read(file);
        // адрес и ключ модели — по умолчанию у синтеза речи, у распознавания и у векторов
        String llmBaseUrl = s.require("llm_base_url", "адрес модели");
        String llmApiKey = s.require("llm_api_key", "ключ модели");
        Config config = new Config(
                Integer.parseInt(s.require("api_id", "идентификатор приложения с my.telegram.org")),
                s.require("api_hash", "ключ приложения с my.telegram.org"),
                s.require("phone", "номер телефона учётной записи в международном формате"),
                s.number("owner_id", 0, "идентификатор владельца: его сообщения важнее всех прочих"),
                s.path("session_dir", "session", "где хранится сессия Telegram"),
                s.path("prompts_dir", "prompts", "папка с промптами"),
                s.path("diary_dir", "data/diary", "папка дневника — долгой памяти"),
                s.path("working_memory_file", "data/working_memory.md", "файл средней памяти"),
                s.integer("diary_injection_max_length", 4000,
                        "сколько букв воспоминаний подмешивать в разговор за раз"),
                s.fraction("diary_plagiarism_threshold", 0.97,
                        "выше этой близости новая запись дневника считается повтором старой"),
                s.fraction("diary_min_relatedness", 0.80,
                        "ниже этой близости запись не вспоминается, как бы ни просел плавающий порог"),
                s.integer("diary_consolidation_length", 8000,
                        "сколько букв записей сводится за один заход пересмотра во сне; "
                                + "больше — сильнее сжимается память, меньше — бережнее"),
                new Vision(
                        s.flag("vision_enabled", true, "видит ли она картинки и видео"),
                        s.get("vision_model", "openai/gpt-4o-mini", "модель зрения для важного"),
                        s.get("vision_model_cheap", "qwen/qwen3.6-flash",
                                "дешёвая модель зрения: стикеры, аватарки"),
                        s.path("vision_cache_dir", "cache/images", "кэш описаний картинок"),
                        s.integer("vision_recent_messages", 8,
                                "у скольких последних сообщений разглядывать вложения"),
                        s.flag("vision_video_in_channels", false,
                                "разбирать ли ролики в каналах покадрово; обычно хватает заставки")),
                new Voice(
                        s.flag("voice_enabled", true, "может ли записывать голосовые"),
                        s.get("voice_provider", "openai", "чем синтезируется речь: openai или fish"),
                        s.get("voice_format", "pcm",
                                "в каком формате просим звук: pcm, wav, mp3 или opus; "
                                        + "что бы ни пришло, разбирается"),
                        s.get("voice_base_url", llmBaseUrl,
                                "адрес синтеза речи; для fish — https://api.fish.audio/v1"),
                        s.get("voice_api_key", llmApiKey,
                                "ключ синтеза речи; для fish — ключ из fish.audio"),
                        s.get("voice_model", "google/gemini-3.1-flash-tts-preview",
                                "модель синтеза речи; для fish — s2.1-pro-free"),
                        s.get("voice_name", "Leda",
                                "голос: для openai — имя, для fish — reference_id голоса"),
                        s.get("voice_language", "ru",
                                "язык речи: без него синтез угадывает его по тексту; для fish не нужен"),
                        s.fraction("voice_speed", 1.2,
                                "скорость речи; для fish не нужен"),
                        s.integer("voice_sample_rate", 24000, "частота дискретизации синтеза"),
                        s.integer("voice_bitrate", 24000, "битрейт голосового сообщения"),
                        s.path("voice_dir", "data/voice_messages", "куда складывать записанные голосовые")),
                new Hearing(
                        s.flag("hearing_enabled", true, "расшифровывать ли звук из присланных видео"),
                        s.get("hearing_base_url", llmBaseUrl,
                                "адрес распознавания речи"),
                        s.get("hearing_api_key", llmApiKey,
                                "ключ распознавания речи"),
                        s.get("hearing_model", "openai/whisper-1", "модель распознавания речи"),
                        s.get("hearing_language", "ru", "язык, на котором говорят в видео")),
                new Camera(
                        s.flag("camera_enabled", true, "может ли делать снимки"),
                        s.get("camera_base_url", "https://aihorde.net/api/v2/", "адрес очереди рисования"),
                        s.get("camera_api_key", "", "ключ очереди рисования"),
                        java.util.List.of(s.get("camera_models",
                                "WAI-NSFW-illustrious-SDXL,AMPonyXL,AlbedoBase XL (SDXL),Nova Anime XL",
                                "модели рисования через запятую").split(",")),
                        s.path("camera_gallery", "data/gallery", "галерея снимков"),
                        s.integer("camera_max_trials", 3, "сколько раз переснимать, пока снимок не удастся"),
                        s.integer("camera_queue_minutes", 60,
                                "сколько минут ждать своей очереди в сети добровольцев")),
                s.get("web_search_url", "https://ollama.com/api/web_search", "адрес поиска в сети"),
                s.get("web_search_key", "", "ключ поиска в сети; пустой — только бесплатный DuckDuckGo"),
                s.get("web_search_provider", "auto",
                        "кто ищет: auto (сначала бесплатный DuckDuckGo, при неудаче платный API), "
                                + "duckduckgo (только бесплатный), ollama (только платный по ключу)"),
                new Llm(
                        llmBaseUrl,
                        s.require("llm_model", "название модели"),
                        llmApiKey,
                        s.fraction("llm_temperature", 0.2, "температура модели")),
                new Embedding(
                        orDefault(s.get("embedding_base_url", llmBaseUrl,
                                "адрес эмбеддингов; пусто — адрес модели; "
                                        + "для локальной модели: http://127.0.0.1:11434/v1"), llmBaseUrl),
                        s.get("embedding_api_key", llmApiKey,
                                "ключ эмбеддингов; пусто — без ключа, у локального сервера он не нужен"),
                        s.get("embedding_model", "openai/text-embedding-3-large",
                                "модель для векторов памяти")),
                new Character(
                        s.get("character_name", "Raine", "имя"),
                        s.get("character_nickname", "@raine_tyan", "ник в Telegram"),
                        s.get("papik_name", "RaineDev", "как она зовёт владельца")),
                s.number("context_token_limit", 40000, "после какого объёма разговор сбрасывается в дневник"),
                s.get("lockdown", "owner_only", "круг общения: owner_only, contacts, everyone"),
                s.flag("lockdown_allow_channels", true, "читать ли каналы при суженном круге общения"),
                s.flag("sleep_consolidation", false, "пересматривать ли память во сне"),
                new Consolidation(
                        s.integer("sleep_consolidation_minutes", 30, "предел минут на пересмотр за ночь"),
                        s.integer("sleep_consolidation_max_requests", 20, "предел запросов: ответы и эмбеддинги за ночь"),
                        s.number("sleep_consolidation_max_tokens", 100000, "предел токенов ответов и подсказок за ночь")),
                new Night(
                        s.flag("night_sleep", true, "спит ли она ночью"),
                        s.time("night_bedtime_from", "23:00", "не раньше этого времени ложится"),
                        s.time("night_bedtime_to", "01:00", "не позже этого времени ложится"),
                        s.time("night_wake_from", "06:00", "не раньше этого времени встаёт"),
                        s.time("night_wake_to", "09:00", "не позже этого времени встаёт")),
                new Around(
                        s.flag("around_enabled", true, "знает ли она время суток и погоду за окном"),
                        s.fraction("around_latitude", 55.75, "широта места, где она «живёт»"),
                        s.fraction("around_longitude", 37.62, "долгота места, где она «живёт»")),
                s.integer("notice_delay_seconds", 30, "до скольких секунд тянуть с ответом"),
                s.integer("web_ui_port", 0,
                        "порт локальной веб-панели (http://127.0.0.1:<порт>) для наблюдения за логами; "
                                + "0 — выключена. Панель слушает только на localhost и ничего не меняет"),
                s.flag("offline_life", true,
                        "жить ли свой день, пока Telegram недоступен: фото, дневник, браузер, мысли. "
                                + "Действия в Telegram при этом всё равно запрещены"),
                s.flag("read_only", true, "только смотреть и ничего не отправлять"),
                s.flag("llm_transcript", true,
                        "записывать ли каждый запрос к модели и ответ в logs/llm — этим разбирают, "
                                + "почему она ответила именно так"),
                new Behaviour(
                        s.integer("chat_max_history_length", 2000,
                                "сколько букв истории чата показывать при открытии"),
                        s.flag("wake_up_on_pinned_chat", false, "будят ли ночью закреплённые чаты"),
                        s.flag("randomly_go_sleep", true, "отлучается ли она изредка среди дня"),
                        s.flag("can_write_to_a_new_person", false,
                                "может ли писать первой тем, с кем переписки ещё не было"),
                        s.flag("use_stickers", true, "пользуется ли стикерами"),
                        s.fraction("tool_reminder_probability", 0.02,
                                "как часто напоминать, что переписка — не только текст")),
                new Repeat(
                        s.fraction("anti_repeat_trigger_max", 0.95,
                                "выше этой близости к одной прошлой реплике — повтор"),
                        s.fraction("anti_repeat_trigger_avg", 0.85,
                                "выше этой средней близости ко всем — хождение по кругу"),
                        s.integer("anti_repeat_max_history", 32,
                                "сколько своих реплик держать для сравнения")),
                new Browser(
                        s.flag("browser_enabled", false,
                                "дать ли ей браузер: живой Firefox, чтобы сидеть на сайтах и в соцсетях"),
                        s.flag("browser_headless", false,
                                "браузер без окна; для TikTok лучше false и запустить под Xvfb"),
                        s.get("browser_engine", "firefox",
                                "движок: firefox (по умолчанию), chromium или webkit"),
                        s.path("browser_profile_dir", "data/browser/profile",
                                "постоянный профиль браузера: в нём остаются входы на сайты; "
                                        + "подкаталог на каждый движок"),
                        s.get("browser_locale", "ru-RU", "язык/регион браузера"),
                        s.get("browser_timezone", "Europe/Moscow", "часовой пояс браузера"),
                        s.integer("browser_viewport_width", 1280, "ширина окна браузера"),
                        s.integer("browser_viewport_height", 800, "высота окна браузера"),
                        s.get("browser_user_agent", "",
                                "строка браузера; пусто — оставить свою"),
                        s.get("browser_login_binary", "",
                                "настоящий браузер для ручного входа (например /usr/bin/firefox); "
                                        + "пусто — найти в системе самому")),
                new TikTok(
                        s.flag("tiktok_enabled", false,
                                "сидеть ли в TikTok со своего аккаунта (лента, лайки, комменты, репосты)"),
                        s.get("tiktok_username", "", "её ник в TikTok — для поиска друзей и своих видео"),
                        s.path("tiktok_download_dir", "data/tiktok", "куда складывать скачанные ролики"),
                        s.integer("tiktok_max_videos_per_session", 12,
                                "сколько роликов смотреть за один заход"),
                        s.fraction("tiktok_like_chance", 0.35, "как часто лайкать просмотренное"),
                        s.fraction("tiktok_comment_chance", 0.06, "как часто комментировать — редко, как человек"),
                        s.fraction("tiktok_repost_chance", 0.04, "как часто репостить"),
                        s.integer("tiktok_daily_like_cap", 60, "предел лайков в сутки"),
                        s.integer("tiktok_daily_comment_cap", 8, "предел комментариев в сутки"),
                        s.integer("tiktok_daily_follow_cap", 5, "предел подписок в сутки"),
                        s.integer("tiktok_daily_dm_cap", 10, "предел личных сообщений в сутки"),
                        s.integer("tiktok_cooldown_minutes", 90, "пауза между сессиями в TikTok, минут"),
                        s.integer("tiktok_warmup_days", 3,
                                "сколько дней с первого входа только смотреть, не действуя")));
        s.sync();
        return config;
    }
}
