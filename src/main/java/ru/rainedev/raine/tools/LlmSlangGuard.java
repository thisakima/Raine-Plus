package ru.rainedev.raine.tools;

import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.core.LowQualityException;

/**
 * Ловит модельные шутки-тики, которые в промпте запрещены, а модель всё равно
 * выдаёт: «это название фильма какое-то», «звучит как сценарий», «прям как в
 * аниме». Запрет словами не работает — в логах они всё равно появляются, и
 * звучат именно как генератор, а не как человек.
 * <p>
 * Работает так же, как {@link AntiRepeat}: бросает {@link LowQualityException},
 * ход откатывается, и модель пробует сказать то же самое обычными словами.
 * Ничего не заменяет и не переписывает за неё — только просит переиграть.
 */
public final class LlmSlangGuard {

    private static final Logger log = LoggerFactory.getLogger(LlmSlangGuard.class);

    /**
     * Тики, по которым человека не спутать с моделью. Каждый — кусок
     * «остроумного» сравнения, которое человек не стал бы проговаривать вслух.
     */
    private static final List<Pattern> TELLS = List.of(
            // «это название фильма/книги/группы какое-то» — самый частый тик.
            // Корень «назва», а не «название»: модель и человек опечатываются
            // («назвагие»), и тик всё равно надо ловить.
            Pattern.compile("(?iu)назва\\p{L}*\\s+(фильма|книги|группы|сериала|песни|трека)"),
            // «звучит как …»
            Pattern.compile("(?iu)звучит\\s+как\\s+(название|фильм|книг|сценар|аниме|сериал|трек|групп)"),
            // «прям как в кино/аниме»
            Pattern.compile("(?iu)(прям|прямо|точь-в-точь|как\\s+будто)\\s+как\\s+в\\s+"
                    + "(кино|аниме|фильме|сериале|сказке|мультике)"),
            // «это уже готовый сценарий»
            Pattern.compile("(?iu)это\\s+(уже\\s+)?готов\\p{L}*\\s+сценари"),
            // «это тянет на сценарий/название»
            Pattern.compile("(?iu)тянет\\s+на\\s+(название|сценари|фильм)"),
            // «X — это когда …»: определение через тире. Без тире не трогаем —
            // «это когда» встречается и в обычной речи.
            Pattern.compile("(?iu)\\s*[—–]\\s*это\\s+когда"),
            // DeepSeek-specific patterns
            Pattern.compile("(?iu)интересн\\p{L}*\\s+факт"),
            Pattern.compile("(?iu)стоит\\s+отмет"),
            Pattern.compile("(?iu)не\\s+стоит\\s+забыв"),
            Pattern.compile("(?iu)как\\s+ни\\s+странно"),
            Pattern.compile("(?iu)во[-–]?\\s*первых"),
            Pattern.compile("(?iu)во[-–]?\\s*вторых"),
            Pattern.compile("(?iu)с\\s+одной\\s+стороны"),
            Pattern.compile("(?iu)с\\s+другой\\s+стороны"),
            Pattern.compile("(?iu)в\\s+контекст\\p{L}*"),
            Pattern.compile("(?iu)позволяет\\s+"),
            Pattern.compile("(?iu)обеспечивает\\s+"),
            Pattern.compile("(?iu)способствует\\s+"),
            Pattern.compile("(?iu)разумеется"),
            Pattern.compile("(?iu)следовательно"),
            Pattern.compile("(?iu)таким\\s+образом"),
            Pattern.compile("(?iu)в\\s+целом"),
            Pattern.compile("(?iu)в\\s+основном"),
            Pattern.compile("(?iu)как\\s+правило"),
            Pattern.compile("(?iu)подробн\\p{L}*\\s+анализ"),
            Pattern.compile("(?iu)детальн\\p{L}*\\s+разбор"),
            Pattern.compile("(?iu)комплексн\\p{L}*\\s+подход"),
            Pattern.compile("(?iu)это\\s+напоминает"),
            Pattern.compile("(?iu)название\\s+фильм\\p{L}*"),
            Pattern.compile("(?iu)я\\s+понимаю"),
            Pattern.compile("(?iu)я\\s+слышу\\s+тебя"),
            Pattern.compile("(?iu)спасибо\\s+за\\s+вопрос"));

    private final String rejection;

    public LlmSlangGuard(String rejection) {
        this.rejection = rejection;
    }

    /**
     * @throws LowQualityException если в сказанном есть модельный тик
     */
    public void check(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        for (Pattern tell : TELLS) {
            if (tell.matcher(text).find()) {
                log.info("Модельный тик в сообщении — переигрываю: {}", shorten(text));
                throw new LowQualityException(rejection);
            }
        }
    }

    /** Только для тестов: есть ли в тексте тик. */
    static boolean looksGenerated(String text) {
        if (text == null) {
            return false;
        }
        for (Pattern tell : TELLS) {
            if (tell.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    private static String shorten(String text) {
        String one = text.replace("\n", " ").strip();
        return one.length() > 120 ? one.substring(0, 120) + "…" : one;
    }
}
