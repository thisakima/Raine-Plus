package ru.rainedev.raine.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Поиск в интернете — для того, чего в дневнике быть не может: погода, новости, факты.
 * <p>
 * Основной источник — DuckDuckGo: он бесплатный, без ключа и без квот. Платный
 * API по ключу остаётся запасным (или основным, если так настроено): у него
 * аккуратные сниппеты, но он заканчивается деньгами — без оплаты отвечает 402,
 * и тогда поиск молча не работал бы вовсе.
 */
public final class WebSearch {

    private static final Logger log = LoggerFactory.getLogger(WebSearch.class);

    private static final int MAX_RESULTS = 5;

    /** Лёгкая версия DuckDuckGo: простой HTML, который легко разобрать, и нет капчи. */
    private static final String DDG_URL = "https://lite.duckduckgo.com/lite/?q=";

    /**
     * Строка браузера для запроса. Без неё поисковик отвечает страницей для
     * роботов или пустотой.
     */
    private static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64; rv:132.0) Gecko/20100101 Firefox/132.0";

    private static final Pattern ANCHOR = Pattern.compile("<a\\b([^>]*)>(.*?)</a>", Pattern.DOTALL);
    private static final Pattern HREF = Pattern.compile("href\\s*=\\s*[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SNIPPET = Pattern.compile(
            "<td[^>]*class\\s*=\\s*[\"']result-snippet[\"'][^>]*>(.*?)</td>", Pattern.DOTALL);
    private static final Pattern TAG = Pattern.compile("<[^>]+>");

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final String url;
    private final String apiKey;
    private final String provider;

    public WebSearch(String url, String apiKey, String provider) {
        this.url = url;
        this.apiKey = apiKey;
        this.provider = provider == null ? "auto" : provider.strip().toLowerCase();
    }

    /** Бесплатный DuckDuckGo есть всегда; платный API — только с ключом. */
    public boolean isAvailable() {
        return !apiOnly() || hasKey();
    }

    private boolean hasKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    private boolean apiOnly() {
        return provider.startsWith("ollama") || provider.equals("api");
    }

    private boolean apiFirst() {
        return apiOnly();
    }

    /** @return размеченные результаты или пояснение, почему их нет */
    public String search(String query) {
        if (!isAvailable()) {
            return "No web search available";
        }
        if (query == null || query.isBlank()) {
            return "Say what to search for.";
        }

        if (apiFirst()) {
            Found api = viaApi(query);
            if (api.ok()) {
                return api.text();
            }
            Found free = viaDuckDuckGo(query);
            // обе дороги не привели ни к чему — отдаём причину платной: она точнее
            return free.ok() ? free.text() : api.text();
        }

        Found free = viaDuckDuckGo(query);
        if (free.ok()) {
            return free.text();
        }
        if (hasKey()) {
            Found api = viaApi(query);
            if (api.ok()) {
                return api.text();
            }
        }
        return free.text();
    }

    /** Нашлось что-нибудь или нет — чтобы отличить пустой ответ от ошибки. */
    private record Found(String text, boolean ok) {}

    private static Found none(String reason) {
        return new Found(reason, false);
    }

    // ───────────────────────────── DuckDuckGo ─────────────────────────────

    private Found viaDuckDuckGo(String query) {
        try {
            HttpRequest request = HttpRequest
                    .newBuilder(URI.create(DDG_URL + URLEncoder.encode(query, StandardCharsets.UTF_8)))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml")
                    .header("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.6")
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.warn("DuckDuckGo вернул {}", response.statusCode());
                return none("Web search failed (%d)".formatted(response.statusCode()));
            }
            List<Hit> hits = parseDuckDuckGo(response.body());
            if (hits.isEmpty()) {
                // пустая страница у DuckDuckGo значит либо «ничего нет», либо
                // «похоже на робота» — обе причины стоит сказать вслух
                return none("Nothing found on the web for: " + query);
            }
            return new Found(renderHits(hits), true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return none("Web search interrupted");
        } catch (Exception e) {
            log.warn("DuckDuckGo не ответил: {}", e.getMessage());
            return none("Web search failed: " + e.getMessage());
        }
    }

    private record Hit(String title, String url, String snippet) {}

    /**
     * Разбор страницы DuckDuckGo lite: ссылка результата, за ней — сниппет.
     * Идём по документу последовательно и каждому результату берём ближайший
     * следующий сниппет — так пары не перепутаются, даже если вёрстка поедет.
     */
    static List<Hit> parseDuckDuckGo(String html) {
        List<Hit> hits = new ArrayList<>();
        if (html == null || html.isBlank()) {
            return hits;
        }
        Matcher anchor = ANCHOR.matcher(html);
        Matcher snippet = SNIPPET.matcher(html);
        while (anchor.find() && hits.size() < MAX_RESULTS) {
            if (!anchor.group(1).contains("result-link")) {
                continue;
            }
            Matcher href = HREF.matcher(anchor.group(1));
            if (!href.find()) {
                continue;
            }
            String target = realUrl(unescape(href.group(1)));
            if (isAd(target)) {
                continue;
            }
            String title = clean(anchor.group(2));
            String text = "";
            if (snippet.find(anchor.end())) {
                text = clean(snippet.group(1));
            }
            if (title.isEmpty() && text.isEmpty()) {
                continue;
            }
            hits.add(new Hit(title, target, text));
        }
        return hits;
    }

    /**
     * DuckDuckGo отдаёт ссылки через свой переход {@code /l/?uddg=<адрес>}:
     * настоящий адрес лежит в параметре и закодирован.
     */
    private static String realUrl(String href) {
        String value = href.startsWith("//") ? "https:" + href : href;
        int at = value.indexOf("uddg=");
        if (at < 0) {
            return value;
        }
        int end = value.indexOf('&', at);
        String encoded = end < 0 ? value.substring(at + "uddg=".length())
                : value.substring(at + "uddg=".length(), end);
        try {
            return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    /** Рекламные врезки DuckDuckGo — не результаты поиска. */
    private static boolean isAd(String url) {
        return url.contains("duckduckgo.com/y.js") || url.contains("duckduckgo.com/l/?khc=");
    }

    // ─────────────────────────── Платный API ───────────────────────────

    private Found viaApi(String query) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("query", query);
            body.put("max_results", MAX_RESULTS);

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(45))
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.warn("Веб-поиск вернул {}: {}", response.statusCode(), shorten(response.body()));
                // причина передаётся как есть: «кончилась квота» и «нет сети» —
                // это разные вещи, и решать, что делать дальше, ей
                return none("Web search failed (%d): %s".formatted(response.statusCode(), shorten(response.body())));
            }
            JsonNode results = mapper.readTree(response.body()).path("results");
            if (!results.isArray() || results.isEmpty()) {
                return none("Nothing found on the web for: " + query);
            }
            List<Hit> hits = new ArrayList<>();
            for (JsonNode result : results) {
                hits.add(new Hit(result.path("title").asText(""),
                        result.path("url").asText(""),
                        result.path("content").asText("")));
            }
            return new Found(renderHits(hits), true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return none("Web search interrupted");
        } catch (Exception e) {
            log.warn("Веб-поиск не удался: {}", e.getMessage());
            return none("Web search failed: " + e.getMessage());
        }
    }

    private static String renderHits(List<Hit> hits) {
        StringBuilder out = new StringBuilder();
        for (Hit hit : hits) {
            out.append("<web_search_result title=\"%s\" url=\"%s\">\n%s\n</web_search_result>\n".formatted(
                    hit.title(), hit.url(), hit.snippet()));
        }
        return out.toString();
    }

    /** Убирает разметку и служебные пробелы, оставляя читаемый текст. */
    private static String clean(String html) {
        return unescape(TAG.matcher(html).replaceAll(" ")).replaceAll("\\s+", " ").strip();
    }

    private static String unescape(String text) {
        return text.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&#x27;", "'")
                .replace("\u200c", "")
                .replace("\u2060", "");
    }

    /** Ответ службы бывает многословным, а в контексте нужна суть. */
    private static String shorten(String body) {
        String text = body == null ? "" : body.strip().replace("\n", " ");
        return text.length() > 300 ? text.substring(0, 300) + "..." : text;
    }
}
