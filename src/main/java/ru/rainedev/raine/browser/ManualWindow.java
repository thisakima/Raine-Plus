package ru.rainedev.raine.browser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import ru.rainedev.raine.config.Config;

/**
 * Окно настоящего браузера для ручного входа — без автоматизации и без сборки
 * Playwright.
 * <p>
 * Почему не тем браузером, которым она пользуется: Playwright водит патченую
 * сборку Firefox, у которой в {@code application.ini} стоит
 * {@code CodeName=Nightly}. Google на ней отвечает «браузер может быть
 * небезопасным, войдите в другом», TikTok — «слишком много попыток» на первой
 * же попытке. Системный Firefox — релизная сборка, и вход в нём проходит.
 * <p>
 * Профиль для входа отдельный ({@link Config.Browser#loginProfile()}): он не
 * совместим с её рабочим по версии, и трогать личный профиль человека тоже
 * нельзя. Поэтому вход переносится в её профиль куками — см. {@link CookieBridge}.
 */
public final class ManualWindow {

    /**
     * Семейство Firefox: у них куки лежат в открытой базе {@code cookies.sqlite},
     * которую можно прочитать. У Chrome и его форков база шифруется ключом из
     * связки ключей, и перенести вход оттуда заметно труднее.
     */
    private static final List<String> LOGIN_BROWSERS = List.of(
            "firefox", "firefox-esr", "librewolf", "waterfox", "floorp");

    private ManualWindow() {}

    /**
     * Открывает окно настоящего браузера на {@code url} и возвращает процесс.
     * Профиль — отдельный, для входа; окно видно на дисплее.
     */
    public static Process open(Config.Browser config, String url) throws IOException {
        Path binary = loginBinary(config);
        Path profile = config.loginProfile().toAbsolutePath();
        Files.createDirectories(profile);

        List<String> command = new ArrayList<>();
        command.add(binary.toString());
        if (isFirefox(binary)) {
            // -no-remote обязателен: иначе Firefox передаст адрес уже открытому
            // окну с чужим профилем, и вход сохранится «не туда»
            command.add("-no-remote");
            command.add("-profile");
            command.add(profile.toString());
        } else {
            command.add("--user-data-dir=" + profile);
            command.add("--no-first-run");
            command.add("--no-default-browser-check");
        }
        command.add(url);
        return new ProcessBuilder(command).inheritIO().start();
    }

    /**
     * Чем открывать окно входа: браузер из настройки, иначе найденный в системе
     * Firefox, и только если его вовсе нет — тот, которым управляет Playwright
     * (с ним вход в Google и TikTok, скорее всего, не пройдёт).
     */
    public static Path loginBinary(Config.Browser config) {
        String configured = config.loginBinary();
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.strip());
        }
        Path found = Programs.firstOf(LOGIN_BROWSERS);
        if (found != null) {
            return found;
        }
        return Browser.engineBinary(config);
    }

    /** Тот ли это браузер, с которого вход можно перенести куками. */
    public static boolean transfersCookies(Config.Browser config) {
        return isFirefox(loginBinary(config));
    }

    private static boolean isFirefox(Path binary) {
        String name = binary.getFileName().toString().toLowerCase();
        return name.contains("firefox") || name.contains("librewolf")
                || name.contains("waterfox") || name.contains("floorp");
    }
}
