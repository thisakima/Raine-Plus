package ru.rainedev.raine.browser;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.config.Config;

/**
 * Тело для браузера: живой Firefox под управлением Playwright.
 * <p>
 * Профиль постоянный — в нём остаются входы на сайты (в том числе в TikTok),
 * так что логиниться заново после перезапуска не нужно. Профиль у каждого
 * движка свой: они несовместимы между собой. Браузер открывается лениво, при
 * первом же действии, и закрывается вместе с приложением.
 * <p>
 * <b>Поток.</b> Playwright требует, чтобы все обращения к одному экземпляру
 * шли из одного потока, а бот живёт на виртуальных потоках. Поэтому браузер
 * держит свой единственный поток, и вся работа с ним идёт через
 * {@link #onPage(Function)} — вызывающий поток (виртуальный) просто ждёт
 * результата.
 */
public final class Browser implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Browser.class);

    /** Сколько ждать закрытия браузера при остановке, чтобы не висеть вечно. */
    private static final long CLOSE_TIMEOUT_SECONDS = 20;

    /** Таймаут действий на странице: загрузка, ожидание элемента. */
    private static final double PAGE_TIMEOUT_MS = 30_000;

    /**
     * Флаги запуска Chromium: первый убирает приметку автоматизации, второй
     * спасает в контейнере с маленькой общей памятью, остальные — чтобы не
     * предлагал «сделать его основным» при каждом старте. Firefox такие флаги
     * не понимает, ему нужны свои.
     */
    private static final List<String> CHROMIUM_ARGS = List.of(
            "--disable-blink-features=AutomationControlled",
            "--disable-dev-shm-usage",
            "--no-first-run",
            "--no-default-browser-check");

    /**
     * Настройки Firefox: убрать первый запуск, телеметрию и вкладку «Что нового»
     * — пустой профиль, который на первом же сайте показывает служебные страницы,
     * выглядит не как браузер человека.
     */
    private static final Map<String, Object> FIREFOX_PREFS = Map.of(
            "browser.shell.checkDefaultBrowser", false,
            "browser.startup.homepage_override.mstone", "ignore",
            "datareporting.policy.dataSubmissionEnabled", false,
            "toolkit.telemetry.enabled", false,
            "app.shield.optoutstudies.enabled", false);

    /**
     * Глубокий стелс для Firefox: маскирует все основные векторы фингерпринтинга,
     * по которым TikTok (и другие) отличают автоматизацию от живого браузера.
     * <p>
     * Скрипт внедряется через {@code context.addInitScript} — выполняется до
     * любого JS на странице, в том числе в подфреймах (плеер TikTok часто в
     * отдельном iframe).
     */
    private static final String STEALTH_SCRIPT = """
            // ===== navigator.webdriver =====
            // У обычного браузера false, у автоматизированного — true.
            // Важно: возвращаем ровно false (boolean), не undefined — typeof проверка.
            Object.defineProperty(navigator, 'webdriver', {get: () => false});

            // ===== navigator.languages =====
            // У автоматизации часто только ['en-US'].
            Object.defineProperty(navigator, 'languages', {
                get: () => Object.freeze(['ru-RU', 'ru', 'en-US', 'en'])
            });

            // ===== navigator.hardwareConcurrency =====
            // Headless/боты часто выдают 1 или 2. Реальный десктоп — 4–16.
            Object.defineProperty(navigator, 'hardwareConcurrency', {get: () => 8});

            // ===== navigator.platform =====
            // Должен совпадать с ОС.
            Object.defineProperty(navigator, 'platform', {get: () => 'Linux x86_64'});

            // ===== navigator.deviceMemory =====
            // В Firefox есть, в headless часто 0.25/0.5 — явная примета.
            Object.defineProperty(navigator, 'deviceMemory', {get: () => 8});

            // ===== navigator.maxTouchPoints =====
            // На десктопе 0, на тачскринах >0. Боты иногда оставляют 0 в headless — ок,
            // но лучше явно задать.
            Object.defineProperty(navigator, 'maxTouchPoints', {get: () => 0});

            // ===== navigator.vendor / vendorSub =====
            // Firefox: vendor пустой, vendorSub пустой. Chromium — "Google Inc.".
            // Не подделываем под Chrome — это сразу сгорит. Оставляем как в Firefox.
            // Object.defineProperty(navigator, 'vendor', {get: () => ''});
            // Object.defineProperty(navigator, 'vendorSub', {get: () => ''});

            // ===== navigator.product / productSub =====
            Object.defineProperty(navigator, 'product', {get: () => 'Gecko'});
            Object.defineProperty(navigator, 'productSub', {get: () => '20030107'});

            // ===== navigator.userAgentData (Client Hints) =====
            // Если есть — подменяем brands/mobile/platform.
            if (navigator.userAgentData) {
                Object.defineProperty(navigator.userAgentData, 'brands', {
                    get: () => Object.freeze([
                        {brand: 'Firefox', version: '120'},
                        {brand: 'Not:A-Brand', version: '8'}
                    ])
                });
                Object.defineProperty(navigator.userAgentData, 'mobile', {get: () => false});
                Object.defineProperty(navigator.userAgentData, 'platform', {get: () => 'Linux'});
            }

            // ===== navigator.permissions =====
            // У автоматизации query может вести себя иначе. Чиним notifications.
            if (navigator.permissions && navigator.permissions.query) {
                const origQuery = navigator.permissions.query.bind(navigator.permissions);
                navigator.permissions.query = (params) => {
                    if (params.name === 'notifications' && params.userVisibleOnly !== undefined) {
                        return Promise.resolve({state: 'prompt', onchange: null});
                    }
                    return origQuery(params);
                };
            }

            // ===== window.Notification =====
            // В headless может отсутствовать — в живом Firefox на Linux есть всегда.
            if (typeof window.Notification === 'undefined') {
                window.Notification = {
                    permission: 'default',
                    requestPermission: () => Promise.resolve('default')
                };
            }

            // ===== screen =====
            // Headless часто отдаёт 800x600 или 0x0. Ставим реалистичные значения.
            // Не ломаем screen.orientation — в Firefox его нет.
            Object.defineProperty(screen, 'width', {get: () => 1920});
            Object.defineProperty(screen, 'height', {get: () => 1080});
            Object.defineProperty(screen, 'availWidth', {get: () => 1920});
            Object.defineProperty(screen, 'availHeight', {get: () => 1040}); // минус панель задач
            Object.defineProperty(screen, 'colorDepth', {get: () => 24});
            Object.defineProperty(screen, 'pixelDepth', {get: () => 24});
            Object.defineProperty(screen, 'deviceXDPI', {get: () => 96});
            Object.defineProperty(screen, 'deviceYDPI', {get: () => 96});
            Object.defineProperty(screen, 'logicalXDPI', {get: () => 96});
            Object.defineProperty(screen, 'logicalYDPI', {get: () => 96});

            // ===== Canvas fingerprinting =====
            // toDataURL / getImageData — основной вектор. Добавляем шум.
            (() => {
                const origToDataURL = HTMLCanvasElement.prototype.toDataURL;
                const origGetImageData = CanvasRenderingContext2D.prototype.getImageData;
                const noise = () => Math.random() * 0.0001; // микро-шумя, невидимый глазу
                HTMLCanvasElement.prototype.toDataURL = function (type, quality) {
                    const ctx = this.getContext('2d');
                    if (ctx) {
                        const imgData = ctx.getImageData(0, 0, this.width, this.height);
                        const data = imgData.data;
                        for (let i = 0; i < data.length; i += 4) {
                            data[i] += noise() * 255;     // R
                            data[i + 1] += noise() * 255; // G
                            data[i + 2] += noise() * 255; // B
                        }
                        ctx.putImageData(imgData, 0, 0);
                    }
                    return origToDataURL.apply(this, arguments);
                };
                CanvasRenderingContext2D.prototype.getImageData = function (x, y, w, h) {
                    const imgData = origGetImageData.apply(this, arguments);
                    const data = imgData.data;
                    for (let i = 0; i < data.length; i += 4) {
                        data[i] += noise() * 255;
                        data[i + 1] += noise() * 255;
                        data[i + 2] += noise() * 255;
                    }
                    return imgData;
                };
            })();

            // ===== WebGL fingerprinting =====
            // VENDOR/RENDERER — выдают драйвер GPU. Подменяем на общие.
            (() => {
                const getParameter = WebGLRenderingContext.prototype.getParameter;
                WebGLRenderingContext.prototype.getParameter = function (param) {
                    // 37445 = UNMASKED_VENDOR_WEBGL, 37446 = UNMASKED_RENDERER_WEBGL
                    if (param === 37445) return 'Mozilla';
                    if (param === 37446) return 'Mozilla';
                    return getParameter.apply(this, arguments);
                };
                // WebGL2
                if (WebGL2RenderingContext) {
                    WebGL2RenderingContext.prototype.getParameter = function (param) {
                        if (param === 37445) return 'Mozilla';
                        if (param === 37446) return 'Mozilla';
                        return getParameter.apply(this, arguments);
                    };
                }
            })();

            // ===== AudioContext fingerprinting =====
            // OfflineAudioContext — стабильный отпечаток железа. Добавляем шум.
            (() => {
                const OrigAudioContext = window.AudioContext || window.webkitAudioContext;
                if (OrigAudioContext) {
                    const origCreateOscillator = OrigAudioContext.prototype.createOscillator;
                    OrigAudioContext.prototype.createOscillator = function () {
                        const osc = origCreateOscillator.apply(this, arguments);
                        const origConnect = osc.connect.bind(osc);
                        osc.connect = (dest) => {
                            // тихий шум на выходе
                            const gain = this.createGain();
                            gain.gain.value = 1e-10;
                            origConnect(gain);
                            gain.connect(dest);
                        };
                        return osc;
                    };
                }
                const OrigOfflineAudioContext = window.OfflineAudioContext || window.webkitOfflineAudioContext;
                if (OrigOfflineAudioContext) {
                    const origStartRendering = OrigOfflineAudioContext.prototype.startRendering;
                    OrigOfflineAudioContext.prototype.startRendering = function () {
                        const promise = origStartRendering.apply(this, arguments);
                        return promise.then(buffer => {
                            const data = buffer.getChannelData(0);
                            for (let i = 0; i < data.length; i++) {
                                data[i] += (Math.random() - 0.5) * 1e-10;
                            }
                            return buffer;
                        });
                    };
                }
            })();

            // ===== Media Devices enumeration =====
            // enumerateDevices выдаёт реальные камеры/микрофоны — уникальный набор.
            // Подменяем на пустой или стандартный набор.
            (() => {
                if (navigator.mediaDevices && navigator.mediaDevices.enumerateDevices) {
                    const origEnumerate = navigator.mediaDevices.enumerateDevices.bind(navigator.mediaDevices);
                    navigator.mediaDevices.enumerateDevices = async () => {
                        const devices = await origEnumerate();
                        // Возвращаем только аудио-вход (микрофон) и видео-вход (вебкам) — как у типичного ноута
                        return devices.filter(d => d.kind === 'audioinput' || d.kind === 'videoinput')
                            .slice(0, 2);
                    };
                }
            })();

            // ===== Battery API =====
            // navigator.getBattery() — может выдать уникальный заряд/статус.
            // В Firefox на десктопе часто нет, но если есть — подменяем.
            (() => {
                if (navigator.getBattery) {
                    const origGetBattery = navigator.getBattery.bind(navigator);
                    navigator.getBattery = () => Promise.resolve({
                        charging: true,
                        chargingTime: 0,
                        dischargingTime: Infinity,
                        level: 1,
                        addEventListener: () => {},
                        removeEventListener: () => {},
                        dispatchEvent: () => true
                    });
                }
            })();

            // ===== WebRTC IP leak protection =====
            // RTCPeerConnection может утекать локальный IP. Переопределяем createOffer.
            (() => {
                if (window.RTCPeerConnection) {
                    const origCreateOffer = RTCPeerConnection.prototype.createOffer;
                    RTCPeerConnection.prototype.createOffer = function (options) {
                        // force relay (TURN) — не даём straight UDP
                        const opts = Object.assign({}, options, {iceTransportPolicy: 'relay'});
                        return origCreateOffer.apply(this, [opts]);
                    };
                }
            })();

            // ===== Font enumeration protection =====
            // measureText на canvas — способ узнать установленные шрифты.
            // Уже покрыто canvas-шумом выше, но можно добавить fallback.
            (() => {
                const origMeasureText = CanvasRenderingContext2D.prototype.measureText;
                CanvasRenderingContext2D.prototype.measureText = function (text) {
                    const metrics = origMeasureText.apply(this, arguments);
                    // невидимая вариация ширины
                    const origWidth = metrics.width;
                    return new Proxy(metrics, {
                        get(target, prop) {
                            if (prop === 'width') return origWidth + (Math.random() - 0.5) * 0.01;
                            return target[prop];
                        }
                    });
                };
            })();

            // ===== Performance/Navigation timing =====
            // performance.now() точность — вектор. Округляем.
            (() => {
                const origNow = performance.now.bind(performance);
                performance.now = () => Math.floor(origNow() * 100) / 100; // 0.01ms точность
            })();

            // ===== Date/timezone consistency =====
            // Уже задаётся через context.setTimezoneId, но дублируем для JS.
            Object.defineProperty(Intl.DateTimeFormat.prototype, 'resolvedOptions', {
                value: function () {
                    const opts = Object.getPrototypeOf(Intl.DateTimeFormat.prototype).resolvedOptions.call(this);
                    return Object.assign({}, opts, {timeZone: 'Europe/Moscow'});
                }
            });
            """;

    /**
     * Дополнительно только для Chromium: {@code window.chrome} у него есть, и
     * антибот проверяет его наличие. Firefox такой подделки не простит — у него
     * этого объекта отродясь нет, и он стал бы приметой.
     */
    private static final String STEALTH_SCRIPT_CHROMIUM = """
            Object.defineProperty(navigator, 'webdriver', {get: () => false});
            window.chrome = window.chrome || {runtime: {}};
            """;

    private final Config.Browser config;
    private final ExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    // всё ниже живёт и меняется только на потоке браузера
    private Playwright playwright;
    private BrowserContext context;
    private Page page;
    private boolean headless;

    public Browser(Config.Browser config) {
        this.config = config;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "raine-browser");
            thread.setDaemon(true);
            return thread;
        });
    }

    public boolean isEnabled() {
        return config.enabled();
    }

    /**
     * Путь к бинарнику движка, которым управляет Playwright.
     * <p>
     * Нужен, чтобы открыть то же окно и тот же профиль обычным процессом, без
     * автоматизации: сайты не видят робота и пускают войти, а вход остаётся в
     * профиле — дальше им пользуется она. Версии обязаны совпадать: Firefox 137
     * не открывает профиль, созданный Firefox 159 (проверено).
     */
    public static Path engineBinary(Config.Browser config) {
        try (Playwright playwright = Playwright.create()) {
            BrowserType type = switch (config.engineName()) {
                case "chromium" -> playwright.chromium();
                case "webkit" -> playwright.webkit();
                default -> playwright.firefox();
            };
            return Path.of(type.executablePath());
        }
    }

    /** Открыт ли браузер прямо сейчас. */
    public boolean isStarted() {
        return context != null;
    }

    /**
     * Выполняет действие на активной странице, открывая браузер, если нужно.
     * Всё, что делает с Playwright любой инструмент, обязано идти через сюда —
     * иначе обращения попадут не на тот поток и Playwright упадёт.
     */
    public <T> T onPage(Function<Page, T> action) {
        return submit(() -> {
            ensureStarted();
            return action.apply(page);
        });
    }

    /** То же, но с доступом к контексту — нужен, например, для новых вкладок. */
    public <T> T onContext(Function<BrowserContext, T> action) {
        return submit(() -> {
            ensureStarted();
            return action.apply(context);
        });
    }

    /**
     * Список вкладок, как полоса вкладок в браузере. Активная помечена «*».
     * Сайты сами открывают новые вкладки (target=_blank), и без этого она
     * оставалась бы на старой, не понимая, куда делся клик.
     */
    public String describeTabs() {
        return submit(() -> {
            ensureStarted();
            List<Page> pages = context.pages();
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < pages.size(); i++) {
                Page tab = pages.get(i);
                out.append(tab == page ? "* " : "  ").append(i).append(": ")
                        .append(text(tab::title, "(no title)"))
                        .append(" — ").append(text(tab::url, "about:blank")).append('\n');
            }
            return out.toString();
        });
    }

    /** Открывает новую вкладку (можно сразу на адрес) и делает её текущей. */
    public String openTab(String url) {
        return submit(() -> {
            ensureStarted();
            Page tab = context.newPage();
            page = tab;
            if (url != null && !url.isBlank()) {
                tab.navigate(url);
            }
            return "Opened a new tab" + (url == null || url.isBlank() ? "." : " on " + url + ".");
        });
    }

    /** Переключается на вкладку по номеру из {@link #describeTabs()}. */
    public String switchTab(int index) {
        return submit(() -> {
            ensureStarted();
            List<Page> pages = context.pages();
            if (index < 0 || index >= pages.size()) {
                return "There is no tab " + index + " — you have " + pages.size() + " open.";
            }
            page = pages.get(index);
            return "Switched to tab " + index + " (" + text(page::title, "no title") + ").";
        });
    }

    /** Закрывает вкладку; текущей становится соседняя. Последнюю не закрываем. */
    public String closeTab(int index) {
        return submit(() -> {
            ensureStarted();
            List<Page> pages = context.pages();
            if (index < 0 || index >= pages.size()) {
                return "There is no tab " + index + " — you have " + pages.size() + " open.";
            }
            Page closing = pages.get(index);
            if (pages.size() == 1) {
                // без страницы браузеру нечего показывать: чистим, но не закрываем
                closing.navigate("about:blank");
                return "That was the last tab — cleared it to a blank page instead of closing.";
            }
            boolean wasActive = closing == page;
            closing.close();
            List<Page> left = context.pages();
            if (wasActive && !left.isEmpty()) {
                page = left.get(Math.min(index, left.size() - 1));
            }
            return "Closed tab " + index + ".";
        });
    }

    private static String text(java.util.function.Supplier<String> value, String fallback) {
        try {
            String result = value.get();
            return result == null || result.isBlank() ? fallback : result;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private <T> T submit(Callable<T> task) {
        if (closed.get()) {
            throw new IllegalStateException("Браузер уже закрыт");
        }
        try {
            return executor.submit(task).get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new RuntimeException(cause == null ? e : cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Браузер прерван", e);
        }
    }

    /** Только на потоке браузера: открывает профиль и первую страницу. */
    private void ensureStarted() {
        if (context != null) {
            return;
        }
        Path profile = config.profileFor();
        try {
            Files.createDirectories(profile);
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось создать профиль браузера " + profile, e);
        }
        if (playwright == null) {
            playwright = Playwright.create();
        }
        String engine = config.engineName();
        BrowserType type = switch (engine) {
            case "chromium" -> playwright.chromium();
            case "webkit" -> playwright.webkit();
            default -> playwright.firefox();
        };
        headless = config.headless();
        try {
            context = type.launchPersistentContext(profile, options(engine, headless));
        } catch (RuntimeException e) {
            // без дисплея (Xvfb) headed-запуск не выйдет — падаем в headless,
            // чтобы она всё равно могла сидеть в сети, а не лежать
            if (headless) {
                throw e;
            }
            log.warn("Не удалось открыть браузер с окном ({}) — пробую без окна. "
                    + "Для окна нужен дисплей, например Xvfb.", e.getMessage());
            headless = true;
            context = type.launchPersistentContext(profile, options(engine, true));
        }
        context.addInitScript("chromium".equals(engine) ? STEALTH_SCRIPT_CHROMIUM : STEALTH_SCRIPT);
        context.setDefaultTimeout(PAGE_TIMEOUT_MS);
        List<Page> pages = context.pages();
        page = pages.isEmpty() ? context.newPage() : pages.get(0);
        log.info("Браузер запущен: {} ({}), профиль {}", engine, headless ? "без окна" : "с окном", profile);
    }

    private BrowserType.LaunchPersistentContextOptions options(String engine, boolean headless) {
        var options = new BrowserType.LaunchPersistentContextOptions()
                .setHeadless(headless)
                .setIgnoreHTTPSErrors(true)
                .setViewportSize(config.viewportWidth(), config.viewportHeight());
        String channel = config.channel();
        if (channel != null && !channel.isBlank()) {
            options.setChannel(channel);
        }
        if ("chromium".equals(engine)) {
            options.setArgs(CHROMIUM_ARGS);
        } else if ("firefox".equals(engine)) {
            options.setFirefoxUserPrefs(FIREFOX_PREFS);
        }
        if (notBlank(config.locale())) {
            options.setLocale(config.locale());
        }
        if (notBlank(config.timezone())) {
            options.setTimezoneId(config.timezone());
        }
        if (notBlank(config.userAgent())) {
            options.setUserAgent(config.userAgent());
        }
        return options;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.submit(() -> {
                if (context != null) {
                    quietly(context::close, "профиль");
                }
                if (playwright != null) {
                    quietly(playwright::close, "playwright");
                }
                return null;
            }).get(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Не удалось аккуратно закрыть браузер: {}", e.getMessage());
        } finally {
            executor.shutdownNow();
        }
    }

    private static void quietly(Runnable action, String what) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.debug("{} закрылся не сразу: {}", what, e.getMessage());
        }
    }
}
