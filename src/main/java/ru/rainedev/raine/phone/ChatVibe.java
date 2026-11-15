package ru.rainedev.raine.phone;

import it.tdlight.jni.TdApi;
import java.util.List;

/**
 * Анализирует последние сообщения в чате и формулирует короткий «вайб-чек»:
 * как здесь принято общаться. Модель видит это при открытии чата и может
 * подстроиться — не копируя чужие фразы, а перенимая энергию.
 * <p>
 * Анализируются только чужие сообщения: свои собственные в расчёт не идут,
 * иначе стиль чата сводился бы к её собственному вчерашнему.
 */
public final class ChatVibe {

    private ChatVibe() {}

    /**
     * @param messages вся история, от старых к новым
     * @param myId     чтобы отсеять свои сообщения
     * @return короткое описание или пустая строка, если анализировать нечего
     */
    public static String describe(List<TdApi.Message> messages, long myId) {
        int others = 0;
        int totalLength = 0;
        int emojiMessages = 0;
        int capsMessages = 0;
        int slangMessages = 0;
        int matMessages = 0;
        int shortMessages = 0;
        int longMessages = 0;

        for (TdApi.Message message : messages) {
            if (message.isOutgoing) {
                continue;
            }
            if (message.senderId instanceof TdApi.MessageSenderUser u && u.userId == myId) {
                continue;
            }
            if (!(message.content instanceof TdApi.MessageText text)) {
                continue;
            }
            if (text.text == null || text.text.text == null) {
                continue;
            }
            String body = text.text.text.strip();
            if (body.isEmpty()) {
                continue;
            }
            others++;
            totalLength += body.length();

            if (hasEmoji(body)) {
                emojiMessages++;
            }
            if (hasCapsWords(body)) {
                capsMessages++;
            }
            if (hasSlang(body)) {
                slangMessages++;
            }
            if (hasMat(body)) {
                matMessages++;
            }
            if (body.length() < 20) {
                shortMessages++;
            }
            if (body.length() > 150) {
                longMessages++;
            }
        }

        if (others < 3) {
            return "";
        }

        double avgLength = (double) totalLength / others;
        double emojiRate = (double) emojiMessages / others;
        double capsRate = (double) capsMessages / others;
        double slangRate = (double) slangMessages / others;
        double matRate = (double) matMessages / others;
        double shortRate = (double) shortMessages / others;
        double longRate = (double) longMessages / others;

        StringBuilder out = new StringBuilder();
        out.append("<chat_vibe>\n");
        out.append("How people talk in this chat right now (mirror the energy, don't copy verbatim):\n");

        // длина сообщений
        if (avgLength < 25 && shortRate > 0.6) {
            out.append("- very short messages, one or two words at a time.\n");
        } else if (avgLength < 50 && shortRate > 0.4) {
            out.append("- short messages, usually a sentence or less.\n");
        } else if (longRate > 0.3) {
            out.append("- people write long messages here, paragraphs are normal.\n");
        } else {
            out.append("- medium-length messages.\n");
        }

        // эмодзи
        if (emojiRate > 0.5) {
            out.append("- lots of emoji in almost every message.\n");
        } else if (emojiRate > 0.2) {
            out.append("- emoji are common.\n");
        } else if (emojiRate < 0.05) {
            out.append("- almost no emoji, plain text.\n");
        }

        // капс
        if (capsRate > 0.3) {
            out.append("- people use CAPS for emphasis a lot, emotional tone.\n");
        }

        // сленг
        if (slangRate > 0.4) {
            out.append("- heavy slang, meme words, internet language.\n");
        } else if (slangRate > 0.15) {
            out.append("- some slang and meme words.\n");
        }

        // мат
        if (matRate > 0.3) {
            out.append("- mat (swear words) is normal and frequent here.\n");
        } else if (matRate > 0.1) {
            out.append("- mat is used occasionally for emphasis.\n");
        } else if (matRate < 0.02 && others > 10) {
            out.append("- no mat, clean language.\n");
        }

        out.append("Pick up the vibe. Match the energy level and register. ");
        out.append("Add your own flavor — don't become a copy of them, just be ");
        out.append("yourself in this specific context.\n");
        out.append("</chat_vibe>\n");

        return out.toString();
    }

    private static boolean hasEmoji(String text) {
        for (int i = 0; i < text.length(); i++) {
            int cp = text.codePointAt(i);
            // emoji ranges (основные): emoticons, misc symbols, supplementary
            if (cp >= 0x1F600 && cp <= 0x1F64F) return true;
            if (cp >= 0x1F300 && cp <= 0x1F5FF) return true;
            if (cp >= 0x1F900 && cp <= 0x1F9FF) return true;
            if (cp >= 0x2600 && cp <= 0x26FF) return true;
            if (cp >= 0x2700 && cp <= 0x27BF) return true;
            if (Character.charCount(cp) == 2) i++;
        }
        return false;
    }

    private static boolean hasCapsWords(String text) {
        String[] words = text.split("\\s+");
        for (String w : words) {
            if (w.length() >= 3 && w.equals(w.toUpperCase()) && w.chars().anyMatch(Character::isLetter)) {
                return true;
            }
        }
        return false;
    }

    private static final String[] SLANG = {
            "кринж", "вайб", "имба", "рофл", "база", "слэй", "мог", "аура", "тильт",
            "дела", "гг", "изи", "защквар", "жиза", "сл", "пон", "кек", "лол",
            "чикен", "краш", "чилить", "тюбик", "масик", "скуф", "альтушка",
            "крч", "кст", "спс", "нзч", "оч", "канеш", "нафиг"
    };

    private static boolean hasSlang(String text) {
        String low = text.toLowerCase();
        for (String word : SLANG) {
            if (low.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private static final String[] MAT = {
            "блять", "бля", "сука", "пиздец", "пизд", "хуй", "хуя", "хуёв",
            "ебат", "ебать", "ебну", "ёб", "заеб", "нахуй", "нахуя",
            "охуеть", "охуен", "пизд", "мудак", "гандон"
    };

    private static boolean hasMat(String text) {
        String low = text.toLowerCase();
        for (String word : MAT) {
            if (low.contains(word)) {
                return true;
            }
        }
        return false;
    }
}
