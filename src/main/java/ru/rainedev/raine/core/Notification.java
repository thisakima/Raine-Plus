package ru.rainedev.raine.core;

/**
 * Событие, требующее внимания: сообщение, напоминание, повод подумать.
 *
 * @param text    что Raine увидит
 * @param tools   действия, доступные немедленно — например, открыть чат
 * @param ownText текст говорит то, чего в переписке не прочесть: снимок готов,
 *                файл называется так-то. Открытый чат такой текст не заменяет,
 *                а встаёт под ним — иначе сказанное пропадёт, не дойдя до неё
 * @param impulse порыв: она сама решила что-то сделать, а не ей написали.
 *                Пока Telegram недоступен, жить можно только с ними
 */
public record Notification(String text, Toolbox tools, boolean ownText, boolean impulse) {

    public Notification(String text, Toolbox tools, boolean ownText) {
        this(text, tools, ownText, false);
    }

    public Notification(String text, Toolbox tools) {
        this(text, tools, false, false);
    }

    public Notification(String text) {
        this(text, new Toolbox(), false, false);
    }

    /** Тот же повод, но помеченный как её собственный порыв. */
    public Notification asImpulse() {
        return new Notification(text, tools, ownText, true);
    }
}
