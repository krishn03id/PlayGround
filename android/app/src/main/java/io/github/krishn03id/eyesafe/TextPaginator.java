package io.github.krishn03id.eyesafe;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

final class TextPaginator {
    static final class Page {
        final String heading;
        final String text;
        Page(String heading, String text) {
            this.heading = heading;
            this.text = text;
        }
    }

    private static final Pattern HEADING = Pattern.compile("(?i)^(chapter|part|section)\\s+.+");
    private static final int MAX_PAGE_CHARS = 2200;

    static List<Page> paginate(String raw, boolean markdown) {
        String normalized = raw.replace("\uFEFF", "").replace("\r\n", "\n").replace('\r', '\n');
        List<Page> pages = new ArrayList<>();
        String heading = "";
        StringBuilder text = new StringBuilder();
        for (String block : normalized.split("\\n\\s*\\n")) {
            block = block.trim();
            if (block.isEmpty()) continue;
            for (String piece : splitHeadingBlocks(block, markdown)) {
                boolean isHeading = markdown ? piece.matches("(?s)^#{1,6}\\s+[^\\n]+$")
                        : HEADING.matcher(piece).matches() && piece.length() < 140;
                if (isHeading) {
                    if (text.length() > 0) {
                        pages.add(new Page(heading, text.toString().trim()));
                        text.setLength(0);
                    }
                    heading = piece.replaceFirst("^#{1,6}\\s+", "");
                    continue;
                }
                String cleaned = markdown ? plainMarkdown(piece) : piece;
                while (!cleaned.isEmpty()) {
                    int remaining = MAX_PAGE_CHARS - text.length() - (text.length() > 0 ? 2 : 0);
                    if (remaining < 120 && text.length() > 0) {
                        pages.add(new Page(heading, text.toString().trim()));
                        heading = "";
                        text.setLength(0);
                        remaining = MAX_PAGE_CHARS;
                    }
                    int cut = Math.min(remaining, cleaned.length());
                    if (cut < cleaned.length()) {
                        int space = cleaned.lastIndexOf(' ', cut);
                        if (space > cut / 2) cut = space;
                        if (cut > 0 && Character.isHighSurrogate(cleaned.charAt(cut - 1))) cut--;
                    }
                    if (text.length() > 0) text.append("\n\n");
                    text.append(cleaned, 0, cut);
                    cleaned = cleaned.substring(cut).trim();
                    if (!cleaned.isEmpty()) {
                        pages.add(new Page(heading, text.toString().trim()));
                        heading = "";
                        text.setLength(0);
                    }
                }
            }
        }
        if (text.length() > 0 || !heading.isEmpty()) pages.add(new Page(heading, text.toString().trim()));
        if (pages.isEmpty()) pages.add(new Page("Document", "(Empty document)"));
        return pages;
    }

    private static List<String> splitHeadingBlocks(String block, boolean markdown) {
        List<String> result = new ArrayList<>();
        if (markdown && block.matches("(?s)^#{1,6}\\s+[^\\n]+\\n.+")) {
            int newline = block.indexOf('\n');
            result.add(block.substring(0, newline));
            result.add(block.substring(newline + 1).trim());
        } else result.add(block);
        return result;
    }

    static String plainMarkdown(String value) {
        return value.replaceAll("(?m)^```[^\\n]*\\n?", "")
                .replaceAll("!\\[([^]]*)]\\([^)]+\\)", "$1")
                .replaceAll("\\[([^]]+)]\\([^)]+\\)", "$1")
                .replaceAll("\\*\\*([^*]+)\\*\\*", "$1")
                .replaceAll("__([^_]+)__", "$1")
                .replaceAll("`([^`]+)`", "$1")
                .replaceAll("(?m)^\\s*[-*+]\\s+", "• ")
                .replaceAll("(?m)^>\\s?", "");
    }

    static String snippet(String text, int offset) {
        if (text.isEmpty()) return "";
        int start = Math.max(0, Math.min(text.length() - 1, offset));
        return text.substring(start, Math.min(text.length(), start + 100)).replaceAll("\\s+", " ").trim();
    }
}
