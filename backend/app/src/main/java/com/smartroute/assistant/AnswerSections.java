package com.smartroute.assistant;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Splits the model's answer into FACTS, RECOMMENDATIONS and UNCERTAINTY.
 *
 * <p>The parse is deliberately forgiving — a heading may arrive as {@code FACTS}, {@code ## Facts} or
 * {@code **FACTS:**} — and deliberately not clever: if the three headings are not all there, it reports that
 * it failed rather than guessing where one section ends. The raw text is returned either way, so a parse
 * failure degrades to showing what the model wrote instead of showing less than it wrote.
 *
 * <p>Why parse at all, rather than ask for JSON? Because the sections are what the reader reads, and a model
 * writing prose into three named sections writes better prose than one filling in a schema. The parse exists
 * to let the UI style the measured part differently from the suggested part.
 */
final class AnswerSections {

    private static final List<String> HEADINGS = List.of("FACTS", "RECOMMENDATIONS", "UNCERTAINTY");

    record Parsed(List<String> facts, List<String> recommendations, List<String> uncertainty, boolean complete) {
    }

    static Parsed parse(String text) {
        List<List<String>> sections = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        boolean[] seen = new boolean[HEADINGS.size()];
        int current = -1;

        for (String rawLine : text.split("\\R")) {
            String line = rawLine.strip();
            int heading = headingIndex(line);
            if (heading >= 0) {
                current = heading;
                seen[heading] = true;
                continue;
            }
            if (current < 0 || line.isEmpty()) {
                continue;
            }
            sections.get(current).add(bulletText(line));
        }

        boolean complete = seen[0] && seen[1] && seen[2];
        return new Parsed(List.copyOf(sections.get(0)), List.copyOf(sections.get(1)),
                List.copyOf(sections.get(2)), complete);
    }

    /** Which heading this line is, or -1. A heading line carries the word and little else. */
    private static int headingIndex(String line) {
        String bare = line.replace("#", "").replace("*", "").replace(":", "").strip()
                .toUpperCase(Locale.ROOT);
        return HEADINGS.indexOf(bare);
    }

    /** Strips one leading bullet or number marker, leaving the sentence. */
    private static String bulletText(String line) {
        String text = line;
        if (text.startsWith("- ") || text.startsWith("* ") || text.startsWith("• ")) {
            text = text.substring(2);
        } else if (text.length() > 2 && Character.isDigit(text.charAt(0)) && text.charAt(1) == '.') {
            text = text.substring(2);
        }
        return text.strip();
    }

    private AnswerSections() {
    }
}
