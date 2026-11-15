package ru.rainedev.raine.browser;

import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.SameSiteAttribute;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Перенос входа из настоящего браузера в её профиль — куками.
 * <p>
 * Зачем это нужно: браузер, которым управляет Playwright, — патченая сборка
 * Firefox (в его {@code application.ini} стоит {@code CodeName=Nightly}), и
 * Google с TikTok не пускают через неё вход в аккаунт: «браузер может быть
 * небезопасным». Настоящий системный Firefox — релизная сборка, и вход в нём
 * проходит. Но профили у них несовместимы: Firefox 137 не открывает профиль,
 * созданный Firefox 159. Поэтому человек входит в настоящем браузере, а сюда
 * переносится только сессия — куки.
 * <p>
 * Куки читаются прямо из базы Firefox ({@code cookies.sqlite}) через {@code sqlite3}:
 * так гарантированно достаются и httpOnly-куки ({@code sessionid}, {@code SID},
 * {@code __Secure-1PSID} и подобные) — именно их теряют расширения-экспортёры,
 * а без них вход не переносится.
 */
public final class CookieBridge {

    private static final Logger log = LoggerFactory.getLogger(CookieBridge.class);

    /** Сколько ждём sqlite3: база небольшая, но профиль может быть занят. */
    private static final long SQLITE_TIMEOUT_SECONDS = 60;

    /**
     * Порог, выше которого срок жизни куки явно в миллисекундах. Firefox 159
     * хранит {@code expiry} в миллисекундах, а Playwright ждёт секунд — и её
     * рабочий Firefox 137 пишет в секундах. В секундах этот порг — год 5138,
     * так что настоящие сроки он не задевает.
     */
    private static final long MILLISECONDS_THRESHOLD = 100_000_000_000L;

    /**
     * Срок для сессионных кук. Без срока кука живёт до закрытия браузера и
     * пропала бы при первом же перезапуске — а именно сессионные куки и есть вход.
     */
    private static final long SESSION_LIFETIME_SECONDS = 365L * 24 * 3600;

    private static final String SELECT_ALL_COOKIES = """
            SELECT
              CASE WHEN isHttpOnly=1 THEN '#HttpOnly_' || host ELSE host END,
              CASE WHEN host LIKE '.%' THEN 'TRUE' ELSE 'FALSE' END,
              path,
              CASE WHEN isSecure=1 THEN 'TRUE' ELSE 'FALSE' END,
              expiry,
              name,
              value
            FROM moz_cookies
            ORDER BY host, name;""";

    private CookieBridge() {}

    /** Есть ли в системе sqlite3 — без него базу Firefox не прочитать. */
    public static boolean sqliteAvailable() {
        return Programs.find("sqlite3") != null;
    }

    /**
     * Читает все куки профиля Firefox. Профиль копируется во временный каталог:
     * базу можно читать, даже если браузер ещё открыт, и уж точно не стоит
     * трогать чужой профиль напрямую.
     */
    public static List<Cookie> exportAll(Path profileDir) throws IOException {
        Path database = profileDir.resolve("cookies.sqlite");
        if (!Files.exists(database)) {
            throw new IOException("В профиле " + profileDir + " нет cookies.sqlite — "
                    + "похоже, браузер там никуда не заходил");
        }
        Path sqlite = Programs.find("sqlite3");
        if (sqlite == null) {
            throw new IOException("Нужен sqlite3, чтобы прочитать базу кук Firefox. "
                    + "На Arch: sudo pacman -S sqlite");
        }

        Path temp = Files.createTempDirectory("raine-cookies");
        try {
            Path copy = temp.resolve("cookies.sqlite");
            Files.copy(database, copy);
            // незаписанный хвост транзакций: без него свежих кук в выборке не будет
            for (String suffix : List.of("-wal", "-shm")) {
                Path side = profileDir.resolve("cookies.sqlite" + suffix);
                if (Files.exists(side)) {
                    Files.copy(side, copy.resolveSibling("cookies.sqlite" + suffix));
                }
            }

            ProcessBuilder builder = new ProcessBuilder(sqlite.toString(),
                    "-separator", "\t", copy.toString(), SELECT_ALL_COOKIES);
            builder.redirectErrorStream(false);
            Process process = builder.start();
            String output;
            try (Stream<String> lines = process.inputReader(StandardCharsets.UTF_8).lines()) {
                output = lines.reduce("", (all, line) -> all + line + "\n");
            }
            String errors;
            try (Stream<String> lines = process.errorReader(StandardCharsets.UTF_8).lines()) {
                errors = lines.reduce("", (all, line) -> all + line + "\n").strip();
            }
            if (!process.waitFor(SQLITE_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("sqlite3 не закончил за " + SQLITE_TIMEOUT_SECONDS + " с");
            }
            if (process.exitValue() != 0) {
                throw new IOException("sqlite3 вернул " + process.exitValue() + ": " + errors);
            }
            return parseNetscape(output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Чтение кук прервано", e);
        } finally {
            deleteRecursively(temp);
        }
    }

    /**
     * Разбор формата Netscape: домен, флаг поддоменов, путь, secure, срок, имя,
     * значение — через табуляцию. Строки с префиксом {@code #HttpOnly_} дают
     * httpOnly-куки.
     */
    static List<Cookie> parseNetscape(String text) {
        List<Cookie> cookies = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return cookies;
        }
        for (String raw : text.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            boolean httpOnly = line.startsWith("#HttpOnly_");
            if (httpOnly) {
                line = line.substring("#HttpOnly_".length());
            } else if (line.startsWith("#")) {
                continue;   // обычный комментарий
            }
            String[] parts = line.split("\t");
            if (parts.length < 7) {
                continue;
            }
            String domain = parts[0].strip();
            String path = parts[2].strip();
            boolean secure = "TRUE".equalsIgnoreCase(parts[3].strip());
            long rawExpiry;
            try {
                rawExpiry = Long.parseLong(parts[4].strip());
            } catch (NumberFormatException e) {
                rawExpiry = 0;
            }
            String name = parts[5].strip();
            if (domain.isEmpty() || name.isEmpty()) {
                continue;
            }
            double expires = expiresAt(rawExpiry);
            // протухшую куку браузер и сам бы выбросил, а вот влив мог бы
            // не принять — и унёс бы за собой всю пачку
            if (expires < System.currentTimeMillis() / 1000.0) {
                continue;
            }
            cookies.add(new Cookie(name, parts[6])
                    .setDomain(domain)
                    .setPath(path.isEmpty() ? "/" : path)
                    .setHttpOnly(httpOnly)
                    .setSecure(secure)
                    .setExpires(expires)
                    .setSameSite(secure ? SameSiteAttribute.NONE : SameSiteAttribute.LAX));
        }
        return cookies;
    }

    /** Срок в секундах, как ждёт Playwright: миллисекунды приводим, сессионным даём год. */
    private static double expiresAt(long raw) {
        if (raw <= 0) {
            return System.currentTimeMillis() / 1000.0 + SESSION_LIFETIME_SECONDS;
        }
        return raw > MILLISECONDS_THRESHOLD ? raw / 1000.0 : raw;
    }

    /**
     * Читает файл экспорта кук в формате Netscape — его отдают расширения
     * «cookies.txt» / «Get cookies.txt» и пишет {@code scripts/}.
     */
    public static List<Cookie> readNetscapeFile(Path file) throws IOException {
        return parseNetscape(Files.readString(file, StandardCharsets.UTF_8));
    }

    /**
     * Вливает куки в её профиль. Возвращает число принятых.
     * <p>
     * Сначала пробуем всей пачкой — так быстрее. Но {@code addCookies} атомарен:
     * одна «трудная» кука (партиционированная, с префиксом {@code __Host-},
     * с непривычным доменом) уносит за собой весь вход. Поэтому при отказе
     * льём по одной и честно считаем, сколько не влилось.
     */
    public static int importInto(Browser browser, List<Cookie> cookies) {
        if (cookies.isEmpty()) {
            return 0;
        }
        try {
            browser.onContext(context -> {
                context.addCookies(cookies);
                return null;
            });
            return cookies.size();
        } catch (RuntimeException e) {
            log.debug("Пачкой куки не влились ({}) — пробую по одной", e.getMessage());
        }
        int accepted = 0;
        for (Cookie cookie : cookies) {
            try {
                browser.onContext(context -> {
                    context.addCookies(List.of(cookie));
                    return null;
                });
                accepted++;
            } catch (RuntimeException e) {
                log.debug("Кука {} для {} не влилась: {}", cookie.name, cookie.domain, e.getMessage());
            }
        }
        return accepted;
    }

    private static void deleteRecursively(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(file -> {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                    log.debug("Не удалось удалить {}", file);
                }
            });
        } catch (IOException e) {
            log.debug("Не удалось убрать временный каталог кук: {}", e.getMessage());
        }
    }
}
