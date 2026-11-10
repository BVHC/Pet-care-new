package com.petcare.module.ai.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Chia đoạn tài liệu (BR-AI-15): cắt theo tiêu đề Markdown {@code #}–{@code ###}, mỗi đoạn 300–1 200 ký tự, chồng lấn 100
 * ký tự khi phải cắt tiếp. Văn bản đem nhúng = tiêu đề tài liệu + tiêu đề mục + nội dung đoạn. Viết lại từ
 * {@code BE/scripts/spike/chunk.mjs} (T56); khác bản spike ở một điểm: đơn vị dài quá mức được cắt cứng, nên không đoạn
 * nào vượt 1 200 ký tự kể cả văn bản không có dấu ngắt.
 */
public final class DocumentChunker {

    static final int MIN = 300;
    static final int MAX = 1200;
    static final int OVERLAP = 100;
    /** Đơn vị lớn nhất còn cho phép ghép với phần chồng lấn mà không vượt {@link #MAX}. */
    private static final int UNIT_MAX = MAX - OVERLAP - 1;
    /** {@code ai_chunks.heading VARCHAR(200)}. */
    private static final int HEADING_MAX = 200;

    private static final Pattern HEADING = Pattern.compile("^#{1,3}\\s+(.*)$");
    private static final Pattern PARAGRAPH_BREAK = Pattern.compile("\\n{2,}");
    private static final Pattern SENTENCE_BREAK = Pattern.compile("(?<=[.!?])\\s+");

    private DocumentChunker() {
    }

    /** Một đoạn đã chia; {@code ordinal} từ 1; {@code heading} null với phần trước tiêu đề đầu tiên. */
    public record Chunk(int ordinal, String heading, String text, String embedText) {
    }

    private static final class Section {
        String heading;
        String text;

        Section(String heading, String text) {
            this.heading = heading;
            this.text = text;
        }
    }

    public static List<Chunk> chunk(String title, String content) {
        List<Section> merged = mergeShortSections(sections(content));
        List<Chunk> chunks = new ArrayList<>();
        for (Section section : merged) {
            for (String piece : split(section.text)) {
                String heading = section.heading == null ? null : truncate(section.heading, HEADING_MAX);
                String embedText = Stream.of(title, heading, piece).filter(s -> s != null && !s.isEmpty())
                        .reduce((a, b) -> a + "\n" + b).orElse(piece);
                chunks.add(new Chunk(chunks.size() + 1, heading, piece, embedText));
            }
        }
        return chunks;
    }

    private static List<Section> sections(String content) {
        List<Section> out = new ArrayList<>();
        String heading = null;
        StringBuilder lines = new StringBuilder();
        for (String line : content.split("\\r?\\n", -1)) {
            Matcher m = HEADING.matcher(line);
            if (m.matches()) {
                addSection(out, heading, lines);
                heading = m.group(1).trim();
                lines.setLength(0);
            } else {
                lines.append(line).append('\n');
            }
        }
        addSection(out, heading, lines);
        return out;
    }

    private static void addSection(List<Section> out, String heading, StringBuilder lines) {
        String text = lines.toString().trim();
        if (!text.isEmpty()) {
            out.add(new Section(heading, text));
        }
    }

    /** Gộp mục ngắn (< MIN) vào mục kế tiếp nếu vẫn ≤ MAX; mục cuối còn ngắn thì gộp vào mục liền trước. */
    private static List<Section> mergeShortSections(List<Section> sections) {
        List<Section> merged = new ArrayList<>();
        Section cur = null;
        for (Section s : sections) {
            if (cur != null && cur.text.length() < MIN && cur.text.length() + s.text.length() + 2 <= MAX) {
                cur.text += "\n\n" + (s.heading == null ? "" : s.heading + "\n") + s.text;
            } else {
                if (cur != null) {
                    merged.add(cur);
                }
                cur = new Section(s.heading, s.text);
            }
        }
        if (cur != null) {
            merged.add(cur);
        }
        if (merged.size() > 1 && merged.get(merged.size() - 1).text.length() < MIN) {
            Section last = merged.remove(merged.size() - 1);
            merged.get(merged.size() - 1).text += "\n\n" + (last.heading == null ? "" : last.heading + "\n") + last.text;
        }
        return merged;
    }

    /** Tách một mục dài thành mảnh ≤ MAX, ưu tiên ranh giới đoạn văn rồi câu; mảnh sau lặp OVERLAP ký tự cuối mảnh trước. */
    private static List<String> split(String text) {
        if (text.length() <= MAX) {
            return List.of(text);
        }
        List<String> pieces = new ArrayList<>();
        String cur = "";
        for (String unit : units(text)) {
            if (!cur.isEmpty() && cur.length() + unit.length() + 1 > MAX) {
                pieces.add(cur);
                String tail = cur.substring(cur.length() - OVERLAP);
                cur = tail.substring(tail.indexOf(' ') + 1) + " " + unit;
            } else {
                cur = cur.isEmpty() ? unit : cur + "\n" + unit;
            }
        }
        if (!cur.isEmpty()) {
            pieces.add(cur);
        }
        return pieces;
    }

    private static List<String> units(String text) {
        List<String> units = new ArrayList<>();
        for (String paragraph : PARAGRAPH_BREAK.split(text)) {
            if (paragraph.length() <= UNIT_MAX) {
                units.add(paragraph);
                continue;
            }
            for (String sentence : SENTENCE_BREAK.split(paragraph)) {
                hardSplit(sentence, units);
            }
        }
        return units;
    }

    /** Cắt cứng đơn vị quá {@link #UNIT_MAX}, ưu tiên khoảng trắng gần cuối. */
    private static void hardSplit(String unit, List<String> out) {
        String rest = unit;
        while (rest.length() > UNIT_MAX) {
            int cut = rest.lastIndexOf(' ', UNIT_MAX);
            cut = cut <= 0 ? UNIT_MAX : cut;
            out.add(rest.substring(0, cut));
            rest = rest.substring(cut).stripLeading();
        }
        if (!rest.isEmpty()) {
            out.add(rest);
        }
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
