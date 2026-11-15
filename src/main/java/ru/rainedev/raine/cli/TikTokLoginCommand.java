package ru.rainedev.raine.cli;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.WaitUntilState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import ru.rainedev.raine.browser.Browser;
import ru.rainedev.raine.browser.CookieBridge;
import ru.rainedev.raine.config.Config;
import ru.rainedev.raine.tiktok.TikTok;

/**
 * Вход в TikTok. Сначала пробуются пути без окна для входа, а если они не дали
 * сессии — всё делает {@link BrowserLoginCommand}: окно настоящего Firefox,
 * вход руками, перенос кук в её профиль.
 * <p>
 * Вход в её собственном окне под управлением Playwright не проходит — TikTok
 * отвечает «слишком много попыток» с первой же попытки на любой способ входа,
 * потому что видит автоматизацию.
 * <p>
 * Дополнительно к общему пути здесь проверяется именно вход в TikTok
 * ({@link TikTok#loggedIn}), а не просто открытая страница.
 */
public final class TikTokLoginCommand {

    /** Стартовая страница: на ней всегда видна кнопка входа. */
    private static final String HOME_URL = "https://www.tiktok.com/";

    /** Имя файла с экспортом кук — лежит рядом с профилем браузера. */
    private static final String COOKIES_NAME = "cookies.txt";

    private TikTokLoginCommand() {}

    public static void run(Config config) {
        Config.Browser b = config.browser();
        System.out.println("Её профиль: " + b.profileFor().toAbsolutePath());
        System.out.println();

        if (loggedIn(config)) {
            System.out.println("Вход в TikTok уже сохранён в профиле — заново логиниться не нужно.");
            System.out.println("Теперь запускай бота: ./gradlew run");
            return;
        }

        // старый путь: экспорт кук скриптом из личного Firefox
        Path cookies = cookiesFile(b);
        if (Files.exists(cookies) && importFromFile(config, cookies) > 0 && loggedIn(config)) {
            System.out.println("Готово: вход перенесён из " + cookies + " — она вошла в TikTok.");
            System.out.println("Теперь запускай бота: ./gradlew run");
            return;
        }

        System.out.println("Входа в TikTok нет — открываю настоящее окно для входа.");
        System.out.println();
        BrowserLoginCommand.run(config, HOME_URL);

        System.out.println();
        if (loggedIn(config)) {
            System.out.println("Готово: она вошла в TikTok, вход сохранён в " + b.profileFor().toAbsolutePath());
            System.out.println("Теперь запускай бота — в свободное время она будет сидеть в ленте.");
        } else {
            System.out.println("Вход в TikTok не подтвердился: страница не показывает аккаунт.");
            System.out.println("Возможно, сессия привязалась к другому браузеру или вход не завершился.");
            System.out.println("Попробуй ещё раз: ./gradlew run --args=\"tiktok-login\"");
        }
    }

    /** Проверяет вход в её профиле — без окна, чтобы не мелькать на экране. */
    private static boolean loggedIn(Config config) {
        try (Browser browser = new Browser(BrowserLoginCommand.quiet(config.browser()))) {
            // сначала куки: они есть сразу, а шапка SPA может ещё рисоваться,
            // и проверка по разметке дала бы ложное «не вошёл»
            if (browser.onContext(TikTok::hasSession)) {
                return true;
            }
            return browser.onPage(page -> {
                page.navigate(HOME_URL, new Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                        .setTimeout(60_000));
                return TikTok.loggedIn(page);
            });
        } catch (RuntimeException e) {
            System.out.println("Не удалось проверить вход: " + BrowserLoginCommand.rootMessage(e));
            return false;
        }
    }

    /**
     * Файл с куками для переноса входа. Смотрит и рядом с базовым каталогом
     * браузера ({@code data/browser/cookies.txt} — туда пишет скрипт), и рядом
     * с профилем движка.
     */
    private static Path cookiesFile(Config.Browser b) {
        Path base = b.profileDir().toAbsolutePath();
        Path besideProfile = base.resolve(COOKIES_NAME);
        if (Files.exists(besideProfile)) {
            return besideProfile;
        }
        Path parent = base.getParent();
        return (parent == null ? base : parent).resolve(COOKIES_NAME);
    }

    /** Вливает куки из файла формата Netscape. Возвращает число принятых. */
    private static int importFromFile(Config config, Path file) {
        List<Cookie> cookies;
        try {
            cookies = CookieBridge.readNetscapeFile(file);
        } catch (IOException e) {
            System.out.println("Не удалось прочитать " + file + ": " + e.getMessage());
            return 0;
        }
        if (cookies.isEmpty()) {
            System.out.println("В " + file + " нет кук в формате Netscape (нужны столбцы через табуляцию).");
            return 0;
        }
        try (Browser browser = new Browser(BrowserLoginCommand.quiet(config.browser()))) {
            int imported = CookieBridge.importInto(browser, cookies);
            System.out.println("Перенёс куки из " + file + ": " + imported + " шт.");
            BrowserLoginCommand.sleep(1_000);
            return imported;
        } catch (RuntimeException e) {
            System.out.println("Не удалось добавить куки в браузер: " + BrowserLoginCommand.rootMessage(e));
            return 0;
        }
    }
}
