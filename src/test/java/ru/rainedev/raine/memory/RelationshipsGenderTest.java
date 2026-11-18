package ru.rainedev.raine.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Пол собеседника определяет ОНА, а не код.
 * <p>
 * Раньше пол угадывался по последней букве имени: «Никита», «Илья», «Фома»
 * становились женщинами, а «Саша» и «Женя» определялись наугад. Теперь код
 * только хранит то, что она записала через #note_about_person, и ничего не
 * подставляет от себя.
 */
class RelationshipsGenderTest {

    @Test
    void groupContextRemembersPeopleWhoSpoke(@TempDir Path dir) {
        Relationships rel = new Relationships(dir.resolve("rel.json"));
        rel.note(1L, "Миша", "всегда шутит про дошик", "warm", "male");
        rel.note(2L, "Лена", "обиделась вчера из-за шутки про код", "cold", "female");

        String ctx = rel.groupContextFor(List.of(1L, 2L, 3L), 3L);
        assertTrue(ctx.contains("Миша"), ctx);
        assertTrue(ctx.contains("дошик"), ctx);
        assertTrue(ctx.contains("Лена"), ctx);
        assertFalse(ctx.contains("id 3"), "последний говорящий в отдельном блоке, не дублируем: " + ctx);
    }

    @Test
    void groupContextSkipsStrangers(@TempDir Path dir) {
        Relationships rel = new Relationships(dir.resolve("rel.json"));
        String ctx = rel.groupContextFor(List.of(42L, 43L), 0L);
        assertEquals("", ctx, "о незнакомцах ничего не пишем");
    }

    @Test
    void unknownByDefaultSoNothingIsGuessed(@TempDir Path dir) {
        Relationships rel = new Relationships(dir.resolve("rel.json"));
        rel.touch(1L, "Саша");

        assertEquals("", rel.genderOf(1L), "пока она не определила — пол неизвестен");
        assertEquals("", rel.genderOf(999L), "и для незнакомого человека тоже");
    }

    @Test
    void sheRecordsWhatSheFiguredOut(@TempDir Path dir) {
        Relationships rel = new Relationships(dir.resolve("rel.json"));
        rel.note(1L, "Саша", "пишет «я пошла», значит девушка", "neutral", "female");
        rel.note(2L, "Никита", "написал «я устал» — парень", "warm", "male");

        assertEquals("f", rel.genderOf(1L), "имя «Саша» не помешало её выводу");
        assertEquals("m", rel.genderOf(2L), "имя «Никита» больше не делает его женщиной");
    }

    @Test
    void acceptsTheWaysSheMightWriteIt(@TempDir Path dir) {
        Relationships rel = new Relationships(dir.resolve("rel.json"));
        rel.note(1L, "a", "x", "neutral", "female");
        rel.note(2L, "b", "x", "neutral", "f");
        rel.note(3L, "c", "x", "neutral", "ЖЕНСКИЙ");
        rel.note(4L, "d", "x", "neutral", "она");
        rel.note(5L, "e", "x", "neutral", "male");
        rel.note(6L, "f", "x", "neutral", "m");
        rel.note(7L, "g", "x", "neutral", "мужской");
        rel.note(8L, "h", "x", "neutral", "он");

        for (long id = 1; id <= 4; id++) {
            assertEquals("f", rel.genderOf(id), "женский вариант для id=" + id);
        }
        for (long id = 5; id <= 8; id++) {
            assertEquals("m", rel.genderOf(id), "мужской вариант для id=" + id);
        }
    }

    @Test
    void garbageDoesNotEraseWhatSheKnows(@TempDir Path dir) {
        Relationships rel = new Relationships(dir.resolve("rel.json"));
        rel.note(1L, "Саша", "девушка", "neutral", "female");

        rel.note(1L, "Саша", "уточнила", "warm", "не знаю");
        assertEquals("f", rel.genderOf(1L), "нечитаемое значение не должно стирать известное");

        rel.note(1L, "Саша", "ещё заметка", "warm", "");
        assertEquals("f", rel.genderOf(1L), "пустой параметр не трогает прежнее значение");
    }

    @Test
    void sheCanCorrectHerself(@TempDir Path dir) {
        Relationships rel = new Relationships(dir.resolve("rel.json"));
        rel.note(1L, "Женя", "вроде парень", "neutral", "male");
        assertEquals("m", rel.genderOf(1L));

        rel.note(1L, "Женя", "оказалась девушка, написала «я пошла»", "neutral", "female");
        assertEquals("f", rel.genderOf(1L), "исправление должно сработать");
    }

    @Test
    void theOldNoteCallDoesNotWipeGender(@TempDir Path dir) {
        Relationships rel = new Relationships(dir.resolve("rel.json"));
        rel.note(1L, "Саша", "девушка", "neutral", "female");

        // обычный вызов без пола — например, заметка про шутку
        rel.note(1L, "Саша", "любит котов", "warm");
        assertEquals("f", rel.genderOf(1L), "заметка без пола не должна его обнулять");
    }

    @Test
    void genderSurvivesARestart(@TempDir Path dir) {
        Path file = dir.resolve("rel.json");
        Relationships first = new Relationships(file);
        first.note(1L, "Саша", "девушка", "neutral", "female");
        first.note(2L, "Никита", "парень", "neutral", "male");

        Relationships reloaded = new Relationships(file);
        assertEquals("f", reloaded.genderOf(1L), "пол переживает перезапуск");
        assertEquals("m", reloaded.genderOf(2L));
    }

    @Test
    void contextTellsHerWhatSheKnowsAndWhatNot(@TempDir Path dir) {
        Relationships rel = new Relationships(dir.resolve("rel.json"));
        rel.note(1L, "Саша", "девушка", "neutral", "female");
        rel.touch(2L, "Кто-то");

        String known = rel.contextFor(1L);
        assertTrue(known.contains("female"), "известный пол показывается: " + known);

        String unknown = rel.contextFor(2L);
        assertTrue(unknown.contains("haven't figured it out"),
                "для неизвестного должно быть сказано, как определить: " + unknown);
        assertFalse(known.contains("haven't figured it out"), known);
    }
}
