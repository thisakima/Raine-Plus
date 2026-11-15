package ru.rainedev.raine.image;

/**
 * Общий интерфейс генератора картинок. Под ним живут и очередь добровольцев
 * (StableHorde), и прямой платный эндпоинт (OpenAI-compatible) — выбирается
 * по конфигу при запуске.
 */
public interface ImageClient {

    boolean isAvailable();

    /**
     * Понимает ли модель ключевые слова с весами ((masterpiece:1.3)) или
     * ждёт обычный связный текст.
     * <p>
     * Разница существенная: чекпойнты Stable Diffusion понимают теги, а
     * grok-imagine, DALL-E и подобные шлюзы — естественное описание
     * кадра, и развёрнутый текст у них выходит заметно лучше.
     */
    default boolean wantsKeywordPrompt() {
        return false;
    }

    /**
     * @param positive положительное описание
     * @param negative что исключить ( Horde учитывает, OpenAI-шлюз может игнорировать)
     * @param width    желаемая ширина
     * @param height   желаемая высота
     * @param cfgScale сила следования промпту (для Horde; OpenAI-шлюз игнорирует)
     * @param steps    число шагов (для Horde; OpenAI-шлюз игнорирует)
     * @return байты готовой картинки
     */
    byte[] generate(String positive, String negative, int width, int height, double cfgScale, int steps);
}
