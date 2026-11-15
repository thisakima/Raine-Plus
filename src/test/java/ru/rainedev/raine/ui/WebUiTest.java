package ru.rainedev.raine.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Локальная панель должна реально отдавать HTML и JSON, а не просто существовать.
 * Каждый тест берёт свободный порт у ОС, потому что 0 в настройке означает
 * «выключено», а не «любой свободный».
 */
class WebUiTest {

    /** Свободный порт: ОС выдаёт, мы сразу отпускаем и используем номер. */
    private static int freePort() throws IOException {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    @Test
    void portZeroMeansDisabled(@TempDir Path dir) throws IOException {
        assertNull(WebUi.start(0, dir.resolve("raine.log"), dir.resolve("gallery")));
    }

    @Test
    void negativePortMeansDisabled(@TempDir Path dir) throws IOException {
        assertNull(WebUi.start(-1, dir.resolve("raine.log"), dir.resolve("gallery")));
    }

    @Test
    void indexReturnsHtml(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("raine.log");
        Files.writeString(log, "2026-11-14 14:00:00 INFO  r.r.RaineApp - старт\n");
        Path gallery = dir.resolve("gallery");
        Files.createDirectories(gallery);
        try (WebUi ui = WebUi.start(freePort(), log, gallery)) {
            HttpClient c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            String html = c.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + ui.port() + "/")).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            assertTrue(html.contains("<title>Yukiko"), "страница должна отдавать HTML панели: " + html.substring(0, Math.min(200, html.length())));
            // New API endpoints
            assertTrue(html.contains("/api/mood"), "страница должна ссылаться на mood: " + html.substring(0, 200));
            assertTrue(html.contains("/api/schedule"), "страница должна ссылаться на schedule: " + html.substring(0, 200));
            assertTrue(html.contains("/api/thoughts"), "страница должна ссылаться на thoughts: " + html.substring(0, 200));
            assertTrue(html.contains("/api/stats"), "страница должна ссылаться на stats: " + html.substring(0, 200));
        }
    }

    @Test
    void tailReturnsRecentLines(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("raine.log");
        StringBuilder sample = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            sample.append("2026-11-14 14:00:").append(String.format("%02d", i))
                    .append(" INFO  r.r.R - line ").append(i).append('\n');
        }
        Files.writeString(log, sample.toString());
        Path gallery = dir.resolve("gallery");
        Files.createDirectories(gallery);
        try (WebUi ui = WebUi.start(freePort(), log, gallery)) {
            HttpClient c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            String body = c.send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + ui.port() + "/api/tail?n=10")).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            assertTrue(body.contains("\"lines\":["), "должен быть массив строк: " + body);
            assertTrue(body.contains("line 49"), "последняя строка должна попасть: " + body);
            assertTrue(body.contains("line 40"), "и десятая с конца тоже: " + body);
            assertFalse(body.contains("\"line 39") || body.contains("\"line 0"),
                    "старше десятой строки быть не должно: " + body);
        }
    }

    @Test
    void statusReflectsDeliveryState(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("raine.log");
        Files.writeString(log, "");
        Path gallery = dir.resolve("gallery");
        Files.createDirectories(gallery);
        try (WebUi ui = WebUi.start(freePort(), log, gallery)) {
            ui.alive(() -> true).customStatus(() -> "тест");
            HttpClient c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            String body = c.send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + ui.port() + "/api/status")).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            assertTrue(body.contains("\"processAlive\":true"), body);
            assertTrue(body.contains("\"telegramOnline\":false"), "без delivery Telegram считается оффлайн: " + body);
            assertTrue(body.contains("\"custom\":\"тест\""), body);
        }
    }

    @Test
    void missingLogFileIsHandledGracefully(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("does-not-exist.log");
        Path gallery = dir.resolve("gallery");
        Files.createDirectories(gallery);
        try (WebUi ui = WebUi.start(freePort(), log, gallery)) {
            HttpClient c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            int code = c.send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + ui.port() + "/api/tail")).build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode();
            assertEquals(200, code, "панель не должна падать, если лога ещё нет");
        }
    }

    @Test
    void tailCapsAtMax(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("raine.log");
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 3000; i++) s.append("line ").append(i).append('\n');
        Files.writeString(log, s.toString());
        Path gallery = dir.resolve("gallery");
        Files.createDirectories(gallery);
        try (WebUi ui = WebUi.start(freePort(), log, gallery)) {
            HttpClient c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            String body = c.send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + ui.port() + "/api/tail?n=999999")).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            int count = body.split("line ").length - 1;
            assertTrue(count <= 2000, "лимит в 2000 строк должен работать, пришло: " + count);
            assertTrue(count >= 1900, "и должны отдать примерно максимум, пришло: " + count);
        }
    }

    @Test
    void listensOnLocalhostOnly(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("raine.log");
        Files.writeString(log, "");
        Path gallery = dir.resolve("gallery");
        Files.createDirectories(gallery);
        try (WebUi ui = WebUi.start(freePort(), log, gallery)) {
            assertTrue(ui.port() > 0, "порт должен быть назначен");
            HttpClient c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            int code = c.send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + ui.port() + "/api/status")).build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode();
            assertEquals(200, code);
        }
    }

    @Test
    void sinceParamReturnsOnlyNewLines(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("raine.log");
        Files.writeString(log, "first\nsecond\n");
        Path gallery = dir.resolve("gallery");
        Files.createDirectories(gallery);
        try (WebUi ui = WebUi.start(freePort(), log, gallery)) {
            HttpClient c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            String first = c.send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + ui.port() + "/api/tail?n=100")).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            int idx = first.indexOf("\"since\":");
            assertTrue(idx >= 0, first);
            int end = first.indexOf('}', idx);
            long cursor = Long.parseLong(first.substring(idx + 8, end));

            Files.writeString(log, "third\nfourth\n", java.nio.file.StandardOpenOption.APPEND);

            String second = c.send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + ui.port() + "/api/tail?n=100&since=" + cursor)).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            assertTrue(second.contains("third"), "новые строки должны прийти: " + second);
            assertTrue(second.contains("fourth"), "новые строки должны прийти: " + second);
            assertFalse(second.contains("first"), "старые не должны повториться: " + second);
        }
    }
}
