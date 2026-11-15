package ru.rainedev.raine.image;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Генерация картинок через OpenAI-совместимый эндпоинт (/v1/images/generations).
 * <p>
 * Поддерживает grok-imagine, DALL-E, stable-diffusion через routerai и прочие
 * шлюзы, отдающие ответ в формате OpenAI: {@code {"data": [{"b64_json": "..."}]}}
 * или {@code {"data": [{"url": "..."}]}}.
 */
public final class OpenAiImageClient implements ImageClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiImageClient.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();

    private final String baseUrl;
    private final String apiKey;
    private final String model;

    public OpenAiImageClient(String baseUrl, String apiKey, List<String> models) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.apiKey = apiKey;
        this.model = models.isEmpty() ? "" : models.get(0);
    }

    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank() && !model.isEmpty();
    }

    /**
     * @param positive описание картинки
     * @param negative что исключить (у некоторых шлюзов поддерживается)
     * @param width    желаемая ширина (округляется до поддерживаемых размеров)
     * @param height   желаемая высота
     * @return байты готовой картинки (PNG или WebP — как пришлёт шлюз)
     */
    @Override
    public boolean wantsKeywordPrompt() {
        return false;   // grok-imagine и подобные ждут обычного описания кадра
    }

    @Override
    public byte[] generate(String positive, String negative, int width, int height,
                           @SuppressWarnings("unused") double cfgScale,
                           @SuppressWarnings("unused") int steps) {
        // отрицательная часть добавляется в сам промпт: у OpenAI-совместимых
        // шлюзов отдельного поля negative prompt обычно нет
        String prompt = (negative == null || negative.isBlank())
                ? positive
                : positive + ". Avoid: " + negative;

        String size = pickSize(width, height);

        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("prompt", prompt);
        body.put("n", 1);
        body.put("size", size);
        // b64_json гарантирует, что картинка придёт в ответе, а не ссылкой,
        // которая может быть недоступна снаружи (внутренний URL шлюза)
        body.put("response_format", "b64_json");

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "images/generations"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofMinutes(3))
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException(
                        "Генерация отказала (%d): %s".formatted(response.statusCode(),
                                shorten(response.body())));
            }
            JsonNode data = mapper.readTree(response.body()).path("data");
            if (!data.isArray() || data.isEmpty()) {
                throw new IllegalStateException("Генерация вернула пустой ответ");
            }
            JsonNode first = data.get(0);
            if (first.hasNonNull("b64_json")) {
                return Base64.getDecoder().decode(first.get("b64_json").asText());
            }
            if (first.hasNonNull("url")) {
                return download(first.get("url").asText());
            }
            throw new IllegalStateException("В ответе нет ни b64_json, ни url");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Генерация прервана", e);
        } catch (IOException e) {
            throw new IllegalStateException("Генерация недоступна: " + e.getMessage(), e);
        }
    }

    private byte[] download(String url) {
        try {
            HttpResponse<byte[]> response = http.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(45)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2 || response.body().length == 0) {
                throw new IllegalStateException("Картинку не удалось забрать (%d)".formatted(response.statusCode()));
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Загрузка прервана", e);
        } catch (IOException e) {
            throw new IllegalStateException("Картинку не удалось забрать: " + e.getMessage(), e);
        }
    }

    /**
     * OpenAI-совместимые шлюзы принимают только определённые размеры.
     * Приводим произвольные 768–1024 к ближайшему поддерживаемому.
     */
    private static String pickSize(int width, int height) {
        double ratio = (double) width / Math.max(1, height);
        if (ratio > 1.2) {
            return "1536x1024";   // landscape
        }
        if (ratio < 0.8) {
            return "1024x1536";   // portrait
        }
        return "1024x1024";
    }

    private static String shorten(String body) {
        if (body == null) {
            return "";
        }
        String text = body.strip().replace("\n", " ");
        return text.length() > 300 ? text.substring(0, 300) + "..." : text;
    }
}
