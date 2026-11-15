package ru.rainedev.raine.memory;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.llm.ChatResponse;
import ru.rainedev.raine.llm.LlmClient;
import ru.rainedev.raine.llm.Message;

/**
 * Превращает сегодняшний разговор и воспоминания в страницу личного дневника.
 * <p>
 * В отличие от {@link DiaryWriter}, здесь нет структуры и векторов: это
 * письмо от первого лица, со всеми «блин», «капец», «я так устала» и
 * прочими признаками живого человека, который пишет себе в тетрадку.
 * <p>
 * Одна страница — один день. Дописывается, если в течение дня случается
 * что-то ещё.
 */
public final class JournalWriter {

    private static final Logger log = LoggerFactory.getLogger(JournalWriter.class);

    private static final int MAX_ATTEMPTS = 2;

    /**
     * Старый дневник подмешивается в промпт, чтобы она не повторялась.
     * Длиннее — контекст распухает, короче — повторений не избежать.
     */
    private static final int RECENT_PAGES = 3;

    private final Journal journal;
    private final Diary diary;
    private final LlmClient llm;
    private final java.util.function.Supplier<String> reflectPrompt;

    public JournalWriter(Journal journal, Diary diary, LlmClient llm,
                         java.util.function.Supplier<String> reflectPrompt) {
        this.journal = journal;
        this.diary = diary;
        this.llm = llm;
        this.reflectPrompt = reflectPrompt;
    }

    /**
     * Пишет (или дописывает) страницу за сегодня. Если сегодня уже писалось,
     * модель получает прежний текст и просится дописать новое, а не
     * переписать всё заново.
     */
    public void reflect(String systemPrompt, List<Message> recentContext) {
        LocalDate today = LocalDate.now();
        String existing = journal.read(today).map(Journal.Page::text).orElse("");
        String recentPages = recentPages();
        String todayFacts = todayFacts();

        StringBuilder brief = new StringBuilder();
        brief.append("<recent_conversations>\n");
        for (Message m : recentContext) {
            String role = m.role() == null ? "" : m.role().name().toLowerCase();
            String content = m.content() == null ? "" : m.content();
            if (content.length() > 600) {
                content = content.substring(0, 600) + "…";
            }
            brief.append(role).append(": ").append(content).append('\n');
        }
        brief.append("</recent_conversations>\n");

        if (!todayFacts.isBlank()) {
            brief.append("\n<today_in_diary>\n").append(todayFacts).append("\n</today_in_diary>\n");
        }
        if (!existing.isBlank()) {
            brief.append("\n<what_i_already_wrote_today>\n")
                    .append(existing)
                    .append("\n</what_i_already_wrote_today>\n")
                    .append("\nDon't repeat what's already written. Add a new section about what ")
                    .append("happened since then — new thoughts, new events, how my mood changed.\n");
        }
        if (!recentPages.isBlank()) {
            brief.append("\n<recent_diary_pages>\n").append(recentPages)
                    .append("\n</recent_diary_pages>\n");
        }

        List<Message> conversation = new ArrayList<>();
        conversation.add(Message.user(brief.toString()));
        conversation.add(Message.user(reflectPrompt.get()));

        String text = "";
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            ChatResponse response = llm.chat(systemPrompt, conversation, null);
            String content = response.text() == null ? "" : response.text().strip();
            if (content.isBlank()) {
                conversation.add(Message.user("Tools are not available. Write the diary page as plain text."));
                continue;
            }
            if (ru.rainedev.raine.memory.ModelText.looksLikeToolCall(content)) {
                conversation.add(Message.user("No tools. Write the diary page as plain text only."));
                continue;
            }
            text = content;
            break;
        }
        if (text.isBlank()) {
            log.warn("Рефлексия дня не получилась — страница не дописана");
            return;
        }
        journal.appendToday(text);
    }

    /**
     * Свежие страницы дневника, кроме сегодняшней — чтобы она не повторяла
     * вчерашнее слово в слово.
     */
    private String recentPages() {
        StringBuilder out = new StringBuilder();
        LocalDate today = LocalDate.now();
        for (Journal.Page page : journal.recent(RECENT_PAGES + 1)) {
            if (page.date().equals(today)) {
                continue;
            }
            out.append("## ").append(page.date()).append("\n")
                    .append(page.text().strip()).append("\n\n");
            if (out.length() > 4000) {
                break;
            }
        }
        return out.toString();
    }

    /**
     * Факты за сегодня из обычного дневника — материал для рефлексии.
     * Без этого она писала бы из воздуха, а так опирается на реальные
     * разговоры и события.
     */
    private String todayFacts() {
        StringBuilder out = new StringBuilder();
        for (Diary.Match match : diary.query(new double[0])) {
            DiaryEntry entry = match.entry();
            if (isToday(entry.id())) {
                out.append("- ").append(entry.body().strip().replace("\n", " ")).append('\n');
                if (out.length() > 3000) {
                    break;
                }
            }
        }
        return out.toString();
    }

    private static boolean isToday(String id) {
        try {
            long epoch = Long.parseLong(id);
            LocalDate day = java.time.Instant.ofEpochSecond(epoch)
                    .atZone(java.time.ZoneId.systemDefault()).toLocalDate();
            return day.equals(LocalDate.now());
        } catch (NumberFormatException | java.time.DateTimeException e) {
            return false;
        }
    }
}
