package ru.rainedev.raine;

import it.tdlight.Init;
import it.tdlight.Log;
import it.tdlight.Slf4JLogMessageHandler;
import it.tdlight.client.APIToken;
import it.tdlight.client.AuthenticationSupplier;
import it.tdlight.client.SimpleTelegramClientFactory;
import it.tdlight.client.TDLibSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.config.Config;

public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        Config config = Config.load();

        // проверить память можно и не поднимая Telegram: так видно ровно одно —
        // запрос и что на него ответил дневник
        if (args.length > 0 && args[0].equals("recall")) {
            ru.rainedev.raine.cli.RecallCommand.run(config, String.join(" ", java.util.Arrays.asList(args).subList(
                    1, args.length)));
            return;
        }

        // один раз войти в TikTok вручную: открываем постоянный профиль браузера
        // без автоматизации и ждём, пока человек залогинится. Telegram для этого
        // не нужен.
        if (args.length > 0 && args[0].equals("tiktok-login")) {
            ru.rainedev.raine.cli.TikTokLoginCommand.run(config);
            return;
        }

        // то же для любого другого сайта: browser-login <url>
        if (args.length > 0 && args[0].equals("browser-login")) {
            String url = args.length > 1
                    ? String.join(" ", java.util.Arrays.asList(args).subList(1, args.length))
                    : "";
            ru.rainedev.raine.cli.BrowserLoginCommand.run(config, url);
            return;
        }

        // вход уже есть в профиле входа — перенести его в её профиль без окна
        if (args.length > 0 && args[0].equals("browser-import")) {
            String url = args.length > 1
                    ? String.join(" ", java.util.Arrays.asList(args).subList(1, args.length))
                    : "";
            ru.rainedev.raine.cli.BrowserLoginCommand.importOnly(config, url);
            return;
        }

        // тест браузера автоматизации (как у неё) — без Telegram, без бота
        if (args.length > 0 && args[0].equals("browser-test")) {
            String url = args.length > 1
                    ? String.join(" ", java.util.Arrays.asList(args).subList(1, args.length))
                    : "";
            ru.rainedev.raine.cli.BrowserTestCommand.run(config, url);
            return;
        }

        Init.init();
        // подробность TDLib: 1 — только ошибки, 3 — видно, что приходит с сервера
        Log.setLogMessageHandler(Integer.getInteger("raine.tdlib.verbosity", 1),
                new Slf4JLogMessageHandler());

        try (SimpleTelegramClientFactory factory = new SimpleTelegramClientFactory()) {
            TDLibSettings settings = TDLibSettings.create(new APIToken(config.apiId(), config.apiHash()));
            settings.setDatabaseDirectoryPath(config.sessionDir().resolve("data"));
            settings.setDownloadedFilesDirectoryPath(config.sessionDir().resolve("downloads"));

            // в списке активных сеансов Telegram видно устройство и приложение.
            // Значения по умолчанию выдают стороннего клиента с первого взгляда
            settings.setDeviceModel("Desktop");
            settings.setSystemVersion("Linux");
            settings.setApplicationVersion("1.0");
            settings.setSystemLanguageCode("ru");

            var builder = factory.builder(settings);
            var authentication = AuthenticationSupplier.user(config.phone());

            // заглянуть и ничего не делать: когда она молчит, первым делом надо
            // понять, дошло ли сообщение вообще
            if (args.length > 0 && args[0].equals("peek")) {
                try (RaineApp app = new RaineApp(builder, authentication, config)) {
                    Thread.sleep(java.time.Duration.ofSeconds(8));
                    ru.rainedev.raine.cli.PeekCommand.run(app.client(), config);
                }
                return;
            }

            try (RaineApp app = new RaineApp(builder, authentication, config)) {
                // Ctrl+C и kill — обычный способ остановить бота, и оба должны
                // давать ей договорить и сохранить разговор, а не рвать на полуслове
                Thread farewell = new Thread(() -> {
                    try {
                        app.close();
                    } catch (Exception e) {
                        log.warn("При остановке что-то пошло не так", e);
                    }
                }, "raine-farewell");
                Runtime.getRuntime().addShutdownHook(farewell);

                app.start();
                app.client().waitForExit();
            }
        }
    }
}
