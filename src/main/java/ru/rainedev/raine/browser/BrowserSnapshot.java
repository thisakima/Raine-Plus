package ru.rainedev.raine.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Page;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * То, что она «видит» на странице: адрес, заголовок, список кликабельных
 * элементов с короткими номерами, основной текст и снимок экрана.
 * <p>
 * Номера ({@code data-raine-ref}) проставляются прямо в DOM, поэтому следующим
 * действием она может сказать «кликни 7» — и {@link BrowserTools} найдёт
 * элемент по {@code [data-raine-ref="7"]}. Это и есть «набор меток»: вместо
 * огромного HTML — компактный список того, с чем можно взаимодействовать.
 * Снимок экрана отдельно разбирает зрение, чтобы она понимала и картинку,
 * а не только подписи кнопок.
 */
public record BrowserSnapshot(String url, String title, List<Mark> marks, String text, byte[] screenshot) {

    private static final Logger log = LoggerFactory.getLogger(BrowserSnapshot.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Столько элементов помечается за раз: больше — контекст захлебнётся. */
    private static final int MAX_MARKS = 120;

    /** Сколько основного текста страницы показывать. */
    private static final int TEXT_LIMIT = 3500;

    /**
     * Один помеченный элемент. Координаты нужны скорее ей, чем клику (клик
     * идёт по {@code ref}), но по ним видно, где что на экране.
     */
    public record Mark(int ref, String tag, String role, String label, int x, int y, int w, int h) {}

    /**
     * Обход интерактивных узлов. Возвращает JSON, а не объекты, — так надёжнее
     * переносится через границу {@code evaluate}. Порядок сверху вниз, как
     * читают страницу, а не как узлы легли в DOM.
     */
    private static final String MARKS_SCRIPT = """
            (() => {
              const sel = 'a[href], button, input, textarea, select, [role="button"], [role="link"], \
            [role="textbox"], [role="menuitem"], [role="tab"], [role="checkbox"], [role="radio"], \
            [role="combobox"], [data-e2e], [onclick], [tabindex]:not([tabindex="-1"])';
              let nodes;
              try { nodes = Array.from(document.querySelectorAll(sel)); } catch (e) { return '[]'; }
              const vh = window.innerHeight;
              const cands = [];
              for (const el of nodes) {
                let r;
                try { r = el.getBoundingClientRect(); } catch (e) { continue; }
                if (r.width <= 1 || r.height <= 1) continue;
                const st = window.getComputedStyle(el);
                if (st.visibility === 'hidden' || st.display === 'none') continue;
                if (r.bottom < 0 || r.top > vh + 4000) continue;
                cands.push([el, r]);
              }
              cands.sort((a, b) => (a[1].top - b[1].top) || (a[1].left - b[1].left));
              const out = [];
              let ref = 1;
              for (const c of cands) {
                if (out.length >= %d) break;
                const el = c[0], r = c[1];
                try { el.setAttribute('data-raine-ref', String(ref)); } catch (e) {}
                const tag = el.tagName ? el.tagName.toLowerCase() : '';
                const role = el.getAttribute('role') || '';
                let label = el.getAttribute('aria-label') || el.getAttribute('placeholder')
                  || el.getAttribute('title') || el.value || el.innerText || el.textContent || '';
                label = String(label).replace(/\\s+/g, ' ').trim();
                if (label.length > 60) label = label.slice(0, 60) + '\\u2026';
                out.push({ref: ref, tag: tag, role: role, label: label,
                  x: Math.round(r.left), y: Math.round(r.top),
                  w: Math.round(r.width), h: Math.round(r.height)});
                ref++;
              }
              return JSON.stringify(out);
            })()
            """.formatted(MAX_MARKS);

    private static final String TEXT_SCRIPT =
            "document.body ? (document.body.innerText || '') : ''";

    /** Снимает состояние страницы. Вызывать на потоке браузера. */
    public static BrowserSnapshot capture(Page page) {
        String url = safe(page::url);
        String title = safe(page::title);
        List<Mark> marks = marks(page);
        String text = visibleText(page);
        byte[] screenshot = screenshot(page);
        return new BrowserSnapshot(url, title, marks, text, screenshot);
    }

    private static List<Mark> marks(Page page) {
        List<Mark> marks = new ArrayList<>();
        try {
            Object result = page.evaluate(MARKS_SCRIPT);
            String json = result == null ? "[]" : result.toString();
            JsonNode array = MAPPER.readTree(json);
            if (!array.isArray()) {
                return marks;
            }
            for (JsonNode node : array) {
                marks.add(new Mark(
                        node.path("ref").asInt(),
                        node.path("tag").asText(""),
                        node.path("role").asText(""),
                        node.path("label").asText(""),
                        node.path("x").asInt(),
                        node.path("y").asInt(),
                        node.path("w").asInt(),
                        node.path("h").asInt()));
            }
        } catch (RuntimeException e) {
            log.debug("Не удалось разметить страницу: {}", e.getMessage());
        } catch (Exception e) {
            log.debug("Не удалось разобрать разметку страницы: {}", e.getMessage());
        }
        return marks;
    }

    private static String visibleText(Page page) {
        String text = safe(() -> {
            Object result = page.evaluate(TEXT_SCRIPT);
            return result == null ? "" : result.toString();
        });
        text = text.replaceAll("[ \\t]+", " ").replaceAll("\\n{3,}", "\n\n").strip();
        return text.length() > TEXT_LIMIT ? text.substring(0, TEXT_LIMIT) + "\u2026" : text;
    }

    private static byte[] screenshot(Page page) {
        try {
            return page.screenshot();
        } catch (RuntimeException e) {
            log.debug("Не удалось снять экран: {}", e.getMessage());
            return null;
        }
    }

    /** Компактный текст для модели: адрес, заголовок, метки, основной текст. */
    public String render() {
        StringBuilder out = new StringBuilder();
        out.append("<browser url=\"").append(url == null ? "" : url)
                .append("\" title=\"").append(escape(title)).append("\">\n");
        if (marks.isEmpty()) {
            out.append("No interactive elements found on this page.\n");
        } else {
            out.append("Interactive elements (pass a ref to browser_click / browser_type):\n");
            for (Mark mark : marks) {
                out.append('[').append(mark.ref()).append(']').append(' ');
                String kind = mark.role().isBlank() ? mark.tag() : mark.role();
                out.append(kind.isEmpty() ? "element" : kind);
                if (!mark.label().isBlank()) {
                    out.append(" \"").append(escape(mark.label())).append('"');
                }
                out.append(" @ (").append(mark.x()).append(',').append(mark.y())
                        .append(") ").append(mark.w()).append('x').append(mark.h()).append('\n');
            }
        }
        out.append("</browser>\n");
        if (text != null && !text.isBlank()) {
            out.append("<visible_text>\n").append(text.strip()).append("\n</visible_text>\n");
        }
        return out.toString();
    }

    /** Элемент по номеру — для клика и ввода. */
    public static String refSelector(int ref) {
        return "[data-raine-ref=\"" + ref + "\"]";
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Обёртка, чтобы один сбой (нет заголовка, нет адреса) не ронял весь снимок. */
    private static String safe(java.util.function.Supplier<String> action) {
        try {
            String value = action.get();
            return value == null ? "" : value;
        } catch (RuntimeException e) {
            return "";
        }
    }
}
