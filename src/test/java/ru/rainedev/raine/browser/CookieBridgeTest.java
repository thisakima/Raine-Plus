package ru.rainedev.raine.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.options.Cookie;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Разбор кук в формате Netscape: от него зависит, перенесётся ли вход.
 * Проверяется без браузера и без базы — на готовом тексте.
 */
class CookieBridgeTest {

    /** Так выглядит строка из базы Firefox: столбцы через табуляцию. */
    private static String row(String host, String path, boolean secure, long expiry,
                              String name, String value, boolean httpOnly) {
        String hostCell = httpOnly ? "#HttpOnly_" + host : host;
        String domainFlag = host.startsWith(".") ? "TRUE" : "FALSE";
        return String.join("\t", hostCell, domainFlag, path, secure ? "TRUE" : "FALSE",
                String.valueOf(expiry), name, value);
    }

    @Test
    void httpOnlyCookiesSurviveTheTransfer() {
        // именно их теряют расширения-экспортёры, а без sessionid вход не переносится
        List<Cookie> cookies = CookieBridge.parseNetscape(
                row(".tiktok.com", "/", true, 1830000000L, "sessionid", "abc123", true));
        assertEquals(1, cookies.size());
        Cookie only = cookies.get(0);
        assertEquals(".tiktok.com", only.domain);
        assertEquals("sessionid", only.name);
        assertEquals("abc123", only.value);
        assertTrue(only.httpOnly, "httpOnly обязан сохраниться");
        assertTrue(only.secure);
        assertEquals("/", only.path);
    }

    @Test
    void sessionCookieGetsALifetime() {
        // кука без срока живёт до закрытия браузера — в её профиле пропала бы сразу
        List<Cookie> cookies = CookieBridge.parseNetscape(
                row(".google.com", "/", true, 0L, "SID", "xyz", true));
        assertEquals(1, cookies.size());
        assertTrue(cookies.get(0).expires > System.currentTimeMillis() / 1000,
                "session-куке нужен срок в будущем, а не 0");
    }

    /**
     * Firefox 159 хранит expiry в миллисекундах, а Playwright ждёт секунд.
     * Без приведения вход уезжал бы в 59947 год и обрезался браузером.
     */
    @Test
    void millisecondExpiryIsConvertedToSeconds() {
        long millis = 1_828_817_757_568L;   // так это лежит в базе Firefox 159
        List<Cookie> cookies = CookieBridge.parseNetscape(
                row(".google.com", "/", true, millis, "__Secure-1PSID", "v", true));
        assertEquals(1, cookies.size());
        assertEquals(millis / 1000.0, cookies.get(0).expires, 0.001);
        // и это действительно разумная дата, а не год 59947
        assertTrue(cookies.get(0).expires < 4_000_000_000L,
                "срок должен остаться в пределах обозримого будущего");
    }

    @Test
    void secondExpiryIsLeftAlone() {
        List<Cookie> cookies = CookieBridge.parseNetscape(
                row(".example.com", "/", true, 1_830_000_000L, "old_format", "v", false));
        assertEquals(1_830_000_000.0, cookies.get(0).expires, 0.001);
    }

    @Test
    void expiredCookieIsSkippedSoItCannotSinkTheBatch() {
        List<Cookie> cookies = CookieBridge.parseNetscape(String.join("\n",
                row(".example.com", "/", false, 1_000_000_000L, "dead", "v", false),
                row(".example.com", "/", false, 1_830_000_000L, "alive", "v", false)));
        assertEquals(1, cookies.size(), "протухшая кука не должна тянуть за собой живые");
        assertEquals("alive", cookies.get(0).name);
    }

    @Test
    void secureFlagIsCarriedOver() {
        // secure-куки идут с SameSite=None, иначе браузер их отвергнет
        List<Cookie> secure = CookieBridge.parseNetscape(
                row(".example.com", "/", true, 1830000000L, "s", "v", false));
        assertEquals(1, secure.size());
        assertTrue(secure.get(0).secure);
        assertFalse(secure.get(0).httpOnly);

        List<Cookie> plain = CookieBridge.parseNetscape(
                row(".example.com", "/", false, 1830000000L, "p", "v", false));
        assertEquals(1, plain.size());
        assertFalse(plain.get(0).secure);
    }

    @Test
    void commentsAndGarbageAreSkipped() {
        String text = String.join("\n",
                "# Netscape HTTP Cookie File",
                "",
                "   ",
                "too\tfew\tcolumns",
                row(".example.com", "/", false, 1830000000L, "good", "yes", false));
        List<Cookie> cookies = CookieBridge.parseNetscape(text);
        assertEquals(1, cookies.size(), "мусор не должен ломать перенос: " + cookies);
        assertEquals("good", cookies.get(0).name);
    }

    @Test
    void emptyInputGivesNoCookies() {
        assertTrue(CookieBridge.parseNetscape("").isEmpty());
        assertTrue(CookieBridge.parseNetscape(null).isEmpty());
    }

    /** Домен без ведущей точки — обычный host, флаг поддоменов не обязателен. */
    @Test
    void hostWithoutLeadingDotIsKept() {
        List<Cookie> cookies = CookieBridge.parseNetscape(
                row("accounts.google.com", "/", true, 1830000000L, "__Secure-1PSID", "v", true));
        assertEquals(1, cookies.size());
        assertEquals("accounts.google.com", cookies.get(0).domain);
    }
}
