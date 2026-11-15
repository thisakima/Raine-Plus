package ru.rainedev.raine.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Что вокруг: время суток и погода за окном.
 * <p>
 * Без этого она живёт вне мира — не знает, что сейчас ночь или что третий день
 * льёт дождь. А человеку такие вещи задают настроение и дают о чём заговорить.
 */
public final class Surroundings {

    private static final Logger log = LoggerFactory.getLogger(Surroundings.class);

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private static final String[] WEEKDAYS = {
            "", "понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье"
    };

    /** Текущее местоположение и активность по времени и дню недели. */
    public record LocationActivity(String location, String activity, boolean isCollege, boolean isHome, boolean isTransit, boolean isNight) {}

    /**
     * Определяет текущее местоположение и активность по времени и дню недели.
     */
    public LocationActivity currentLocationActivity() {
        java.time.LocalDateTime nowDT = java.time.LocalDateTime.now();
        LocalTime now = nowDT.toLocalTime();
        java.time.DayOfWeek dow = nowDT.getDayOfWeek();
        boolean weekend = dow == java.time.DayOfWeek.SATURDAY || dow == java.time.DayOfWeek.SUNDAY;
        int hour = now.getHour();

        if (nowDT.getDayOfWeek() == java.time.DayOfWeek.SATURDAY || 
            nowDT.getDayOfWeek() == java.time.DayOfWeek.SUNDAY) {
            // Выходные
            if (now.getHour() >= 23 || now.getHour() < 8) {
                return new LocationActivity("дома", "спит", false, true, false, true);
            } else if (now.getHour() < 11) {
                return new LocationActivity("дома", "просыпается, кофе, ленивое утро", false, true, false, false);
            } else if (now.getHour() < 14) {
                return new LocationActivity("дома / прогулка", "дома, возможно прогулка", false, true, false, false);
            } else if (now.getHour() < 18) {
                return new LocationActivity("дома / друзья / прогулка", "отдыхает, может гулять с друзьями", false, true, false, false);
            } else if (now.getHour() < 23) {
                return new LocationActivity("дома", "игры, аниме, гитара, отдых", false, true, false, false);
            } else {
                return new LocationActivity("дома", "готовится ко сну, ночные разговоры", false, true, false, false);
            }
        } else {
            // Будни
            if (now.getHour() >= 2 && now.getHour() < 7) {
                return new LocationActivity("дома", "спит", false, true, false, true);
            } else if (now.getHour() < 9) {
                return new LocationActivity("дома", "подъём, кофе, собирается в колледж", false, true, false, false);
            } else if (now.getHour() < 15) {
                return new LocationActivity("колледж", "на паре", true, false, false, false);
            } else if (now.getHour() < 16) {
                return new LocationActivity("колледж", "перемена / обед", true, false, false, false);
            } else if (now.getHour() < 17) {
                return new LocationActivity("колледж / дорога", "последняя пара / идет домой", true, false, true, false);
            } else if (now.getHour() < 18) {
                return new LocationActivity("дорога домой", "в транспорте, слушает музыку", false, false, true, false);
            } else if (now.getHour() < 23) {
                return new LocationActivity("дома", "отдыхает, игры, аниме, гитара", false, true, false, false);
            } else {
                return new LocationActivity("дома", "готовится ко сну, ночные разговоры", false, true, false, false);
            }
        }
    }

    /** Погода меняется медленно — спрашивать чаще незачем. */
    private static final Duration WEATHER_FRESHNESS = Duration.ofMinutes(30);

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private final boolean enabled;
    private final double latitude;
    private final double longitude;

    private String weather = "";
    private Instant weatherAsOf = Instant.EPOCH;

    public Surroundings(boolean enabled, double latitude, double longitude) {
        this.enabled = enabled;
        this.latitude = latitude;
        this.longitude = longitude;
    }

    /** Дополнение к системному промпту — короткое, оно читается каждый раз. */
    public String asPromptSuffix() {
        if (!enabled) {
            return "";
        }
        java.time.LocalDateTime nowDT = java.time.LocalDateTime.now();
        LocalTime now = nowDT.toLocalTime();
        java.time.DayOfWeek dow = nowDT.getDayOfWeek();
        boolean weekend = dow == java.time.DayOfWeek.SATURDAY || dow == java.time.DayOfWeek.SUNDAY;
        StringBuilder out = new StringBuilder("<around_you>\nСейчас ")
                .append(WEEKDAYS[dow.getValue()]).append(", ")
                .append(partOfDay(now)).append(", ").append(TIME.format(now))
                .append(weekend ? ", выходной." : ", будний день.");

        String outside = currentWeather();
        if (!outside.isEmpty()) {
            out.append(" За окном ").append(outside).append('.');
        }

        // время-зависимое состояние: голод, усталость, энергия.
        // живой человек в 14:00 голодный, в 22:00 уставший, в 9:00 сонный.
        // влияет на тон, длину ответов, желание болтать
        String state = timeState(now);
        if (!state.isEmpty()) {
            out.append(' ').append(state);
        }

        return out.append("\n</around_you>\n").toString();
    }

    /**
     * Как она себя чувствует в зависимости от времени суток И места.
     */
    private static String timeState(LocalTime now, LocationActivity locAct) {
        int hour = now.getHour();
        
        if (locAct.isCollege) {
            if (hour >= 8 && hour < 15) {
                return "На паре в колледже. Отвечает с задержкой, коротко, может с опечатками — пишет с телефона под партой. ";
            }
            if (hour >= 15 && hour < 17) {
                return "Перемена в колледже. Может ответить быстрее, но всё равно коротко. ";
            }
            if (hour >= 15 && hour < 17) {
                return "В дороге из колледжа. Пишет в транспорте, может с опечатками. ";
            }
        }
        
        if (locAct.isHome) {
            if (hour >= 22 || hour < 7) {
                return "Дома, поздно. Усталая, пишет лениво, много опечаток, голосовые вместо текста. ";
            }
            if (hour >= 17 && hour < 22) {
                return "Дома, вечер. Отвечает быстро, может писать длинно, голосовые, мемы. ";
            }
            if (hour >= 11 && hour < 17) {
                return "Дома, день. Отвечает нормально, может писать длинно. ";
            }
        }
        
        if (locAct.isTransit) {
            return "В транспорте. Пишет с телефона, коротко, с опечатками. ";
        }
        
        if (locAct.isNight) {
            return "Полночь. Полусонная, коротко, опечатки, мысли путаются. ";
        }
        
        return "";
    }

    /**
     * Как она себя чувствует в зависимости от времени суток. Это делает
     * ответы ритмичными: утром сонная, днём живая, вечером уставшая,
     * ночью полусонная. Не просто декорация — реально влияет на то,
     * как и что она пишет.
     */
    private static String timeState(LocalTime now) {
        int hour = now.getHour();
        if (hour >= 2 && hour < 6) {
            return "Я должна спать. Если отвечаю — то полусонно, коротко, "
                    + "с задержками. Мысли путаются.";
        }
        if (hour >= 6 && hour < 9) {
            return "Только проснулась или просыпаюсь. Сонная, медленная, "
                    + "нужен кофе чтобы включиться.";
        }
        if (hour >= 11 && hour < 14) {
            return "Начинаю хотеть есть, если не обедала.";
        }
        if (hour >= 14 && hour < 16) {
            return "Сытая после обеда (или голодная, если пропустила). "
                    + "Немного вялая после еды.";
        }
        if (hour >= 17 && hour < 20) {
            return "Уже хочется домой или уже дома, отдыхаю.";
        }
        if (hour >= 20 && hour < 23) {
            return "Вечер, устала за день. Хочется залипнуть в телефон, "
                    + "ничего не делать.";
        }
        if (hour >= 23 || hour < 2) {
            return "Поздно, глаза слипаются, но не хочется ложиться. "
                    + "Ленивые ответы, опечатки, короткие сообщения.";
        }
        return "";
    }

    static String partOfDay(LocalTime time) {
        int hour = time.getHour();
        if (hour < 5) {
            return "глубокая ночь";
        }
        if (hour < 11) {
            return "утро";
        }
        if (hour < 17) {
            return "день";
        }
        if (hour < 23) {
            return "вечер";
        }
        return "ночь";
    }

    private String currentWeather() {
        if (Duration.between(weatherAsOf, Instant.now()).compareTo(WEATHER_FRESHNESS) < 0) {
            return weather;
        }
        weatherAsOf = Instant.now();
        try {
            String url = ("https://api.open-meteo.com/v1/forecast?latitude=%s&longitude=%s"
                    + "&current=temperature_2m,weather_code").formatted(latitude, longitude);
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return weather;
            }
            JsonNode current = mapper.readTree(response.body()).path("current");
            int temperature = (int) Math.round(current.path("temperature_2m").asDouble());
            weather = "%s, %+d".formatted(describe(current.path("weather_code").asInt(-1)), temperature);
            log.debug("Погода обновлена: {}", weather);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // без погоды разговор беднее, но продолжается
            log.debug("Погода недоступна: {}", e.getMessage());
        }
        return weather;
    }

    /** Коды погоды по стандарту, которым отвечает служба прогноза. */
    static String describe(int code) {
        return switch (code) {
            case 0 -> "ясно";
            case 1, 2 -> "переменная облачность";
            case 3 -> "пасмурно";
            case 45, 48 -> "туман";
            case 51, 53, 55, 56, 57 -> "морось";
            case 61, 63, 80, 81 -> "дождь";
            case 65, 82 -> "ливень";
            case 66, 67 -> "ледяной дождь";
            case 71, 73, 75, 77, 85, 86 -> "снег";
            case 95, 96, 99 -> "гроза";
            default -> "непонятная погода";
        };
    }
}
