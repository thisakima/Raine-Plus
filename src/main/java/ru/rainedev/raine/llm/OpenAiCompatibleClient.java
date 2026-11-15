package ru.rainedev.raine.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.config.Config;

/**
 * Клиент к любому OpenAI-совместимому эндпоинту (routerai, openrouter, ollama).
 * <p>
 * Стриминг сознательно не используется: в агентном цикле важен цельный ответ
 * с вызовами инструментов, а не постепенный вывод текста.
 */
public final class OpenAiCompatibleClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleClient.class);
    private static final int MAX_ATTEMPTS = 3;

    /**
     * Предел длины ответа. Без него модель изредка застревает в бесконечном
     * рассуждении, и ход упирается в предел времени вместо ответа.
     */
    private static final int MAX_OUTPUT_TOKENS = 8192;

    /**
     * Признак разговора. Посредник держит по нему кэш подсказки: при двух
     * десятках шагов на одно уведомление разница видна прямо в счёте.
     */
    private final String sessionId = java.util.UUID.randomUUID().toString();

    private final ObjectMapper mapper = new ObjectMapper();
    @FunctionalInterface
    interface Sender {
        HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException;
    }

    private final Sender sender;
    private final java.util.function.Consumer<Duration> pause;

    private final Config.Llm config;
    private volatile LlmFailure blocked;
    /** Предел модели эмбеддингов — 8192 токена; на кириллице это заметно меньше букв. */
    private static final int MAX_EMBEDDING_CHARS = 8_000;

    private final Config.Embedding embedding;

    public OpenAiCompatibleClient(Config.Llm config, Config.Embedding embedding) {
        this(config, embedding, defaultSender(), OpenAiCompatibleClient::sleep);
    }

    OpenAiCompatibleClient(Config.Llm config, Config.Embedding embedding, Sender sender,
                           java.util.function.Consumer<Duration> pause) {
        this.config = config;
        this.embedding = embedding;
        this.sender = sender;
        this.pause = pause;
    }

    private static Sender defaultSender() {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
        return request -> http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Override
    public String modelName() {
        return config.model();
    }

    @Override
    public ChatResponse chat(String systemPrompt, List<Message> history, JsonNode tools) {
        List<Message> messages = new ArrayList<>();
        messages.add(Message.system(systemPrompt));
        messages.addAll(history);

        ObjectNode body = mapper.createObjectNode();
        body.put("model", config.model());
        body.put("temperature", config.temperature());
        body.put("max_tokens", MAX_OUTPUT_TOKENS);
        body.put("session_id", sessionId);
        body.set("messages", mapper.valueToTree(messages));
        if (tools != null && tools.isArray() && !tools.isEmpty()) {
            body.set("tools", tools);
            body.put("tool_choice", "auto");
        }

        String response = post(config.baseUrl(), config.apiKey(), "chat/completions", body,
                Duration.ofMinutes(5));
        Transcript.save(body, response);
        try {
            ChatResponse parsed = mapper.readValue(response, ChatResponse.class);
            if (parsed.cacheLooksBroken()) {
                log.warn("Посредник не кэширует подсказку — каждый шаг оплачивается как первый");
            }
            return parsed;
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось разобрать ответ модели", e);
        }
    }

    @Override
    public String describeImage(String model, String systemPrompt, String context, byte[] image) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);

        var messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt);

        // картинка идёт отдельной частью сообщения, а не тегом внутри текста:
        // так её видит любой OpenAI-совместимый шлюз. Границы вложения при этом
        // всё равно называются словами: иначе при нескольких картинках непонятно,
        // где кончается одна и начинается другая
        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");
        var parts = userMessage.putArray("content");
        parts.addObject().put("type", "text").put("text", context);
        parts.addObject().put("type", "text").put("text", "<attachments>");
        parts.addObject()
                .put("type", "image_url")
                .putObject("image_url")
                .put("url", "data:" + mimeOf(image) + ";base64," + java.util.Base64.getEncoder().encodeToString(image));
        parts.addObject().put("type", "text").put("text", "</attachments>");

        String response = post(config.baseUrl(), config.apiKey(), "chat/completions", body,
                Duration.ofMinutes(3));
        try {
            return mapper.readValue(response, ChatResponse.class).text();
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось разобрать описание картинки", e);
        }
    }

    @Override
    public double[] embedding(String input) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", embedding.model());
        // пустую строку эндпоинт не принимает, а пустой текст встречается
        body.put("input", input == null || input.isBlank() ? " " : trimmed(input));

        String response = post(embedding.baseUrl(), embedding.apiKey(), "embeddings", body,
                Duration.ofMinutes(2));
        try {
            JsonNode vector = mapper.readTree(response).path("data").path(0).path("embedding");
            if (!vector.isArray()) {
                throw new IllegalStateException("Эндпоинт не вернул вектор: " + response);
            }
            double[] result = new double[vector.size()];
            for (int i = 0; i < result.length; i++) {
                result[i] = vector.get(i).asDouble();
            }
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось разобрать эмбеддинг", e);
        }
    }

    /**
     * У модели эмбеддингов свой предел длины, и превышение она встречает
     * отказом, а не усечением: вектор не считается, и на той стороне просто
     * ничего не вспоминается. Лучше посчитать вектор по началу текста,
     * чем не посчитать вовсе.
     */
    private static String trimmed(String input) {
        if (input.length() <= MAX_EMBEDDING_CHARS) {
            return input;
        }
        log.debug("Текст для вектора длиннее допустимого, обрезан: {} → {} символов",
                input.length(), MAX_EMBEDDING_CHARS);
        return input.substring(0, MAX_EMBEDDING_CHARS);
    }

    private String post(String baseUrl, String apiKey, String path, ObjectNode body, Duration timeout) {
        LlmFailure unavailable = blocked;
        if (unavailable != null) throw unavailable;
        HttpRequest request;
        try {
            var builder = HttpRequest.newBuilder(URI.create(withSlash(baseUrl) + path))
                    .header("Content-Type", "application/json")
                    // у локального эмбеддера ключа может не быть: на пустой Bearer
                    // некоторые прокси отвечают отказом
                    .timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            if (apiKey != null && !apiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            builder.header("x-session-id", sessionId);
            request = builder.build();
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось собрать запрос", e);
        }

        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> response = sender.send(request);
                if (response.statusCode() / 100 == 2) {
                    return response.body();
                }
                LlmFailure failure = new LlmFailure(response.statusCode(),
                        "Ошибка эндпоинта (%d): %s".formatted(response.statusCode(), response.body()));
                if (!failure.retryable()) {
                    if (failure.requiresOperator()) blocked = failure;
                    throw failure;
                }
                lastFailure = failure;
                if (attempt < MAX_ATTEMPTS) {
                    Duration delay = retryDelay(response.headers().firstValue("Retry-After").orElse(""), attempt);
                    log.warn("Эндпоинт {}, повтор через {} с", response.statusCode(), delay.toSeconds());
                    pause.accept(delay);
                    continue;
                }
            } catch (IOException e) {
                lastFailure = new UncheckedIOException("Сеть недоступна", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Запрос прерван", e);
            }

            if (attempt < MAX_ATTEMPTS) {
                log.warn("Попытка {} из {} не удалась: {}", attempt, MAX_ATTEMPTS, lastFailure.getMessage());
                pause.accept(Duration.ofSeconds(2L * attempt));
            }
        }
        throw lastFailure;
    }

    static Duration retryDelay(String header, int attempt) {
        long seconds = 2L * attempt;
        try { seconds = Math.max(seconds, Long.parseLong(header.strip())); }
        catch (NumberFormatException ignored) {
            try {
                var date = java.time.ZonedDateTime.parse(header, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME);
                seconds = Math.max(seconds, Duration.between(java.time.Instant.now(), date.toInstant()).toSeconds());
            } catch (java.time.format.DateTimeParseException invalid) { /* use backoff */ }
        }
        return Duration.ofSeconds(Math.clamp(seconds, 1, 3600));
    }

    private static String withSlash(String url) {
        return url.endsWith("/") ? url : url + "/";
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Ожидание прервано", e);
        }
    }

    private static String mimeOf(byte[] b) {
        if (b.length >= 12 && b[0] == 0x52 && b[1] == 0x49 && b[2] == 0x46 && b[3] == 0x46
                && b[8] == 0x57 && b[9] == 0x45 && b[10] == 0x42 && b[11] == 0x50) {
            return "image/webp";
        }
        if (b.length >= 4 && (b[0] & 0xFF) == 0x89 && b[1] == 0x50 && b[2] == 0x4E && b[3] == 0x47) {
            return "image/png";
        }
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) {
            return "image/jpeg";
        }
        if (b.length >= 3 && b[0] == 0x47 && b[1] == 0x49 && b[2] == 0x46) {
            return "image/gif";
        }
        return "image/jpeg";
    }
}
