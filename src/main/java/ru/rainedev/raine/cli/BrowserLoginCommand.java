package ru.rainedev.raine.cli;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.WaitUntilState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import ru.rainedev.raine.browser.Browser;
import ru.rainedev.raine.browser.CookieBridge;
import ru.rainedev.raine.browser.ManualWindow;
import ru.rainedev.raine.config.Config;

/**
 * Вход на сайт — в настоящем браузере, с переносом сессии в её профиль.
 * <p>
 * Вход в её собственном окне не проходит: браузер, которым управляет Playwright,
 * — патченая nightly-сборка, и Google отвечает «браузер может быть небезопасным»,
 * а TikTok — «слишком много попыток» с первой же попытки. Поэтому:
 * <ol>
 *   <li>открывается окно настоящего системного Firefox с отдельным профилем для
 *       входа — там автоматизации нет, и сайты пускают;</li>
 *   <li>человек входит и закрывает окно;</li>
 *   <li>куки из этого профиля переносятся в её рабочий профиль — дальше сайтом
 *       пользуется она, уже вошедшей.</li>
 * </ol>
 * Профиль для входа отдельный ещё и потому, что версии несовместимы: Firefox 137,
 * которым управляет Playwright, не открывает профиль, созданный Firefox 159.
 * <p>
 * Запуск: {@code ./gradlew run --args="browser-login https://www.tiktok.com"}
 */
public final class BrowserLoginCommand {

    private static final String DEFAULT_URL = "https://www.tiktok.com/";

    /** Сколько ждём, пока человек войдёт и закроет окно. */
    private static final long TIMEOUT_MINUTES = 30;

    private BrowserLoginCommand() {}

    public static void run(Config config, String url) {
        Config.Browser b = config.browser();
        String target = url == null || url.isBlank() ? DEFAULT_URL : url.strip();
        Path loginProfile = b.loginProfile().toAbsolutePath();

        Path binary = ManualWindow.loginBinary(b);
        System.out.println("Настоящий браузер для входа: " + binary);
        System.out.println("Профиль для входа (отдельный, не её рабочий и не твой личный): " + loginProfile);
        System.out.println("Её рабочий профиль: " + b.profileFor().toAbsolutePath());
        if (!ManualWindow.transfersCookies(b)) {
            System.out.println();
            System.out.println("ВНИМАНИЕ: это не Firefox — куки из его базы достать не получится");
            System.out.println("(у Chrome база шифруется). Вход может не перенестись.");
        }
        System.out.println();
        System.out.println("Сейчас откроется окно на " + target);
        System.out.println("Войди в аккаунт в нём и закрой окно — я перенесу вход в её профиль.");
        System.out.println();

        Process window;
        try {
            window = ManualWindow.open(b, target);
        } catch (IOException e) {
            System.out.println("Не удалось открыть окно: " + e.getMessage());
            return;
        }

        if (!waitFor(window)) {
            System.out.println("Не дождался закрытия окна за " + TIMEOUT_MINUTES + " мин.");
            System.out.println("Если вход уже сделан — закрой окно и запусти команду снова.");
            return;
        }

        // профиль дописывается на диск не мгновенно
        sleep(2_000);
        transfer(config, loginProfile, target);
    }

    /**
     * Перенос входа из профиля для входа в её рабочий профиль — без окна.
     * Нужно, когда человек уже вошёл раньше, а перенос не состоялся или
     * прошёл до входа.
     */
    public static void importOnly(Config config, String url) {
        String target = url == null || url.isBlank() ? DEFAULT_URL : url.strip();
        Path loginProfile = config.browser().loginProfile().toAbsolutePath();
        System.out.println("Профиль входа: " + loginProfile);
        if (!Files.isDirectory(loginProfile)) {
            System.out.println("Профиля входа нет — сначала войди:");
            System.out.println("  ./gradlew run --args=\"browser-login " + target + "\"");
            return;
        }
        transfer(config, loginProfile, target);
    }

    /** Переносит вход из профиля для входа в её рабочий профиль и проверяет. */
    private static void transfer(Config config, Path loginProfile, String target) {
        if (!CookieBridge.sqliteAvailable()) {
            System.out.println();
            System.out.println("Не удалось перенести вход: в системе нет sqlite3.");
            System.out.println("На Arch: sudo pacman -S sqlite");
            return;
        }

        List<Cookie> cookies;
        try {
            cookies = CookieBridge.exportAll(loginProfile);
        } catch (IOException e) {
            System.out.println();
            System.out.println("Не удалось прочитать куки: " + e.getMessage());
            return;
        }
        if (cookies.isEmpty()) {
            System.out.println();
            System.out.println("В профиле для входа нет ни одной куки — похоже, вход не состоялся.");
            System.out.println("Запусти команду заново и войди в аккаунт в открывшемся окне.");
            return;
        }

        Config.Browser b = config.browser();
        Config.Browser quiet = quiet(b);
        int imported;
        String seen;
        try (Browser browser = new Browser(quiet)) {
            imported = CookieBridge.importInto(browser, cookies);
            // профиль должен записаться на диск до выхода — иначе куки пропадут
            seen = browser.onPage(page -> {
                page.navigate(target, new Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                        .setTimeout(60_000));
                return page.url() + " — «" + page.title() + "»";
            });
            sleep(1_000);
        } catch (RuntimeException e) {
            System.out.println();
            System.out.println("Не удалось открыть её браузер: " + rootMessage(e));
            return;
        }

        System.out.println();
        System.out.println("Перенёс кук: " + imported + " шт.");
        System.out.println("Её браузер открыл " + target + " и видит: " + seen);
        System.out.println();
        System.out.println("Если страница показывает вход/логин — сессия не перенеслась");
        System.out.println("(сайт мог привязать её к другому браузеру). Тогда зайди заново");
        System.out.println("и повтори, либо войди в её окне напрямую: tiktok-login.");
        System.out.println("Если видно твой аккаунт — готово, она вошла.");
    }

    /**
     * Её браузер без окна: окно для входа уже было, а здесь нужно только
     * проверить, что сессия перенеслась.
     */
    static Config.Browser quiet(Config.Browser b) {
        return new Config.Browser(true, true, b.engine(), b.profileDir(), b.locale(), b.timezone(),
                b.viewportWidth(), b.viewportHeight(), b.userAgent(), b.loginBinary());
    }

    static boolean waitFor(Process window) {
        try {
            return window.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            window.destroy();
            return true;
        }
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.toString() : root.getMessage();
    }
}
