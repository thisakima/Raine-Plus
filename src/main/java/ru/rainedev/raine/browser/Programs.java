package ru.rainedev.raine.browser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Поиск внешних программ в PATH — без вызова оболочки. */
final class Programs {

    private Programs() {}

    static Path find(String program) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String dir : path.split(":")) {
            if (dir.isBlank()) {
                continue;
            }
            Path candidate = Path.of(dir, program);
            if (Files.isExecutable(candidate) && !Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    static Path firstOf(List<String> names) {
        for (String name : names) {
            Path found = find(name);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
