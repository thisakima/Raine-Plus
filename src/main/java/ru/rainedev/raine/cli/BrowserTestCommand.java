package ru.rainedev.raine.cli;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Scanner;
import ru.rainedev.raine.browser.Browser;
import ru.rainedev.raine.browser.BrowserTools;
import ru.rainedev.raine.browser.HumanInput;
import ru.rainedev.raine.config.Config;
import java.util.random.RandomGenerator;

/**
 * Test Yuki's browser automation directly — without running the full bot.
 * Opens the same browser profile, same tools, same human-like input.
 * Useful to verify the browser works, test selectors, debug issues.
 *
 * Run: {@code ./gradlew run --args="browser-test [url]"}
 */
public final class BrowserTestCommand {

    private static final String DEFAULT_URL = "https://www.tiktok.com/";

    private BrowserTestCommand() {}

    public static void run(Config config, String url) {
        Config.Browser b = config.browser();
        String target = url == null || url.isBlank() ? DEFAULT_URL : url.strip();

        System.out.println("=== Yuki Browser Test ===");
        System.out.println("Profile: " + b.profileFor().toAbsolutePath());
        System.out.println("Engine: " + b.engineName() + " (" + b.channel() + ")");
        System.out.println("Headless: " + b.headless());
        System.out.println("Viewport: " + b.viewportWidth() + "x" + b.viewportHeight());
        System.out.println("Target: " + target);
        System.out.println();

        // Use Yuki's EXACT browser config (headed if configured so)
        try (Browser browser = new Browser(b)) {
            System.out.println("Browser started successfully.");
            System.out.println("Opening " + target + "...");

            // Navigate and wait
            String seen = browser.onPage(page -> {
                page.navigate(target, new Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                        .setTimeout(60_000));
                try {
                    page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(30_000));
                } catch (RuntimeException e) {
                    System.out.println("Network idle timeout (continuing): " + e.getMessage());
                }
                return page.url() + " — «" + page.title() + "»";
            });

            System.out.println("Opened: " + seen);
            System.out.println();

            // Create the EXACT same tools Yuki has
            var humanInput = new HumanInput(RandomGenerator.getDefault());
            var browserTools = new BrowserTools(browser, humanInput, null,
                    Path.of("data/browser/bookmarks.md"), Path.of("data/camera"));

            // Run quick automated test first
            System.out.println("=== Quick Automated Test ===");
            runQuickTest(browser, browserTools);
            System.out.println();

            System.out.println("=== Interactive Mode ===");
            System.out.println("Type commands (or 'help'):");
            System.out.println("  open <url>        - navigate to URL");
            System.out.println("  snap              - take browser_snapshot");
            System.out.println("  click <ref>       - click element by ref");
            System.out.println("  type <ref> <text> - type into field");
            System.out.println("  scroll [down|up]  - scroll page");
            System.out.println("  search <query>    - web search");
            System.out.println("  download <ref>    - download file");
            System.out.println("  shot              - screenshot");
            System.out.println("  tabs              - list tabs");
            System.out.println("  newtab [url]      - new tab");
            System.out.println("  switch <n>        - switch tab");
            System.out.println("  close             - close browser and exit");
            System.out.println("  quit              - exit");
            System.out.println();

            Scanner scanner = new Scanner(System.in);
            while (true) {
                System.out.print("> ");
                String line = scanner.nextLine();
                if (line == null) break; // EOF
                line = line.trim();
                if (line.isEmpty()) continue;

                String[] parts = line.split("\\s+", 3);
                String cmd = parts[0].toLowerCase();

                try {
                    switch (cmd) {
                        case "help", "h" -> printHelp();
                        case "open", "o" -> {
                            if (parts.length < 2) { System.out.println("Usage: open <url>"); break; }
                            String result = browser.onPage(page -> {
                                page.navigate(normalizeUrl(parts[1]), new Page.NavigateOptions()
                                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                                        .setTimeout(60_000));
                                return page.url() + " — «" + page.title() + "»";
                            });
                            System.out.println("Opened: " + result);
                        }
                        case "snap", "s" -> {
                            String result = browserTools.snapshot().call(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode());
                            System.out.println(result);
                        }
                        case "click", "c" -> {
                            if (parts.length < 2) { System.out.println("Usage: click <ref>"); break; }
                            int ref = Integer.parseInt(parts[1]);
                            String result = browserTools.click().call(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                                    .put("ref", ref));
                            System.out.println(result);
                        }
                        case "type", "t" -> {
                            if (parts.length < 3) { System.out.println("Usage: type <ref> <text>"); break; }
                            int ref = Integer.parseInt(parts[1]);
                            String text = parts[2];
                            String result = browserTools.type().call(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                                    .put("ref", ref).put("text", text).put("submit", false));
                            System.out.println(result);
                        }
                        case "scroll" -> {
                            String dir = parts.length > 1 ? parts[1] : "down";
                            String result = browserTools.scroll().call(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                                    .put("direction", dir));
                            System.out.println(result);
                        }
                        case "search" -> {
                            if (parts.length < 2) { System.out.println("Usage: search <query>"); break; }
                            String result = browserTools.webSearch().call(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                                    .put("query", parts[1]));
                            System.out.println(result);
                        }
                        case "download", "dl" -> {
                            if (parts.length < 2) { System.out.println("Usage: download <ref>"); break; }
                            int ref = Integer.parseInt(parts[1]);
                            String result = browserTools.download().call(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                                    .put("ref", ref));
                            System.out.println(result);
                        }
                        case "shot" -> {
                            String result = browserTools.screenshot().call(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                                    .put("full_page", false));
                            System.out.println(result);
                        }
                        case "tabs" -> {
                            String result = browserTools.tabs().call(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode());
                            System.out.println(result);
                        }
                        case "newtab", "nt" -> {
                            String url2 = parts.length > 1 ? parts[1] : "";
                            String result = browserTools.newTab().call(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                                    .put("url", url2));
                            System.out.println(result);
                        }
                        case "switch", "sw" -> {
                            if (parts.length < 2) { System.out.println("Usage: switch <tab>"); break; }
                            int tab = Integer.parseInt(parts[1]);
                            String result = browserTools.switchTab().call(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                                    .put("tab", tab));
                            System.out.println(result);
                        }
                        case "close" -> {
                            System.out.println("Closing browser...");
                            return;
                        }
                        case "quit", "q", "exit" -> {
                            System.out.println("Exiting...");
                            return;
                        }
                        default -> System.out.println("Unknown command: " + cmd + ". Type 'help'.");
                    }
                } catch (Exception e) {
                    System.out.println("Error: " + e.getMessage());
                }
            }
        } catch (RuntimeException e) {
            System.out.println("Browser error: " + BrowserLoginCommand.rootMessage(e));
        }
    }

    private static void runQuickTest(Browser browser, BrowserTools tools) {
        System.out.println("1. Testing browser_snapshot...");
        try {
            String snap = tools.snapshot().call(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode());
            System.out.println("   OK - snapshot captured (" + snap.length() + " chars)");
        } catch (Exception e) {
            System.out.println("   FAILED: " + e.getMessage());
        }

        System.out.println("2. Testing web_search...");
        try {
            String search = tools.webSearch().call(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                    .put("query", "test"));
            System.out.println("   OK - search page loaded (" + search.length() + " chars)");
        } catch (Exception e) {
            System.out.println("   FAILED: " + e.getMessage());
        }

        System.out.println("3. Testing open (duckduckgo)...");
        try {
            String open = tools.open().call(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                    .put("url", "https://duckduckgo.com"));
            System.out.println("   OK - " + open.split("\n")[0]);
        } catch (Exception e) {
            System.out.println("   FAILED: " + e.getMessage());
        }

        System.out.println("4. Testing tabs...");
        try {
            String tabs = tools.tabs().call(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode());
            System.out.println("   OK - " + tabs.split("\n")[0]);
        } catch (Exception e) {
            System.out.println("   FAILED: " + e.getMessage());
        }

        System.out.println("Quick test complete.");
    }

    private static void printHelp() {
        System.out.println("Commands:");
        System.out.println("  open <url>        - navigate to URL");
        System.out.println("  snap              - take browser_snapshot");
        System.out.println("  click <ref>       - click element by ref");
        System.out.println("  type <ref> <text> - type into field");
        System.out.println("  scroll [down|up]  - scroll page");
        System.out.println("  search <query>    - web search");
        System.out.println("  download <ref>    - download file");
        System.out.println("  shot              - screenshot");
        System.out.println("  tabs              - list tabs");
        System.out.println("  newtab [url]      - new tab");
        System.out.println("  switch <n>        - switch tab");
        System.out.println("  close             - close browser and exit");
        System.out.println("  quit              - exit");
    }

    private static String normalizeUrl(String url) {
        String trimmed = url.strip();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed;
        }
        return "https://" + trimmed;
    }
}