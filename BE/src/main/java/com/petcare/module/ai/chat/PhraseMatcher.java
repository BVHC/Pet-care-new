package com.petcare.module.ai.chat;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Khớp cụm từ có ranh giới từ, ưu tiên dạng có dấu; dạng bỏ dấu chỉ dùng cho cụm từ từ 2 âm tiết trở lên (BR-AI-03,
 * BR-AI-26), để "giá" không trùng "già", "tiêm" không trùng "tiệm" và "liệt" không trùng "liệt kê".
 */
final class PhraseMatcher {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");

    private PhraseMatcher() {
    }

    /** NFC, chữ thường, gộp khoảng trắng. */
    static String norm(String text) {
        return WHITESPACE.matcher(Normalizer.normalize(text, Normalizer.Form.NFC).toLowerCase(Locale.ROOT)).replaceAll(" ")
                .trim();
    }

    /** Bỏ dấu thanh và dấu mũ; {@code đ} thành {@code d}. */
    static String strip(String text) {
        return MARKS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("").replace('đ', 'd');
    }

    static boolean hasPhrase(String text, String phrase) {
        return !spans(text, phrase).isEmpty();
    }

    /**
     * Mọi lần xuất hiện của cụm từ, dạng {@code [bắt đầu, kết thúc)} trong {@link #nfc} của {@code text}; cùng luật với
     * {@link #hasPhrase} (ranh giới từ, dạng bỏ dấu chỉ cho cụm từ 2 âm tiết trở lên). Một nguồn sự thật cho "câu có nhắc
     * cụm này không" và "che cụm này ở đâu" (BR-AI-12b dùng cùng quy tắc với BR-AI-26).
     */
    static List<int[]> spans(String text, String phrase) {
        String p = norm(phrase);
        if (p.isEmpty()) {
            return List.of();
        }
        String lower = lowerKeepingLength(nfc(text));
        List<int[]> found = new ArrayList<>(matches(bounded(p), lower, 0));
        if (p.indexOf(' ') >= 0) {
            addNew(found, matches(bounded(strip(p)), stripKeepingLength(lower), 0));
        }
        return found;
    }

    /**
     * Vị trí của {@code name} khi đi ngay sau một trong {@code prefixes} (bé, con, chú, em): dùng cho tên trùng từ thông
     * dụng. Chỉ trả vị trí của tên, không gồm tiền tố. Luôn xét thêm dạng bỏ dấu vì "tiền tố + tên" có từ 2 âm tiết trở lên.
     */
    static List<int[]> spansAfterPrefix(String text, Collection<String> prefixes, String name) {
        String n = norm(name);
        if (n.isEmpty() || prefixes.isEmpty()) {
            return List.of();
        }
        String lower = lowerKeepingLength(nfc(text));
        List<int[]> found = new ArrayList<>(matches(afterPrefix(prefixes, n), lower, 1));
        addNew(found, matches(afterPrefix(prefixes.stream().map(PhraseMatcher::strip).toList(), strip(n)),
                stripKeepingLength(lower), 1));
        return found;
    }

    /** NFC: dạng chuẩn để vị trí khớp với chuỗi mà bên che dùng. */
    static String nfc(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFC);
    }

    static boolean anyPhrase(String text, Collection<String> phrases) {
        return phrases.stream().anyMatch(phrase -> hasPhrase(text, phrase));
    }

    private static Pattern bounded(String phrase) {
        return Pattern.compile("(?<![\\p{L}\\p{N}])" + words(phrase) + "(?![\\p{L}\\p{N}])");
    }

    private static Pattern afterPrefix(Collection<String> prefixes, String name) {
        String alternatives = prefixes.stream().map(prefix -> words(norm(prefix))).collect(Collectors.joining("|"));
        return Pattern.compile("(?<![\\p{L}\\p{N}])(?:" + alternatives + ")\\s+(" + words(name) + ")(?![\\p{L}\\p{N}])");
    }

    /** Các từ của cụm, nối bằng khoảng trắng bất kỳ: câu hỏi có thể gõ hai dấu cách hay xuống dòng giữa hai từ. */
    private static String words(String phrase) {
        return Arrays.stream(phrase.split(" ")).map(Pattern::quote).collect(Collectors.joining("\\s+"));
    }

    private static List<int[]> matches(Pattern pattern, String text, int group) {
        List<int[]> found = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            found.add(new int[] {matcher.start(group), matcher.end(group)});
        }
        return found;
    }

    private static void addNew(List<int[]> found, List<int[]> more) {
        for (int[] span : more) {
            if (found.stream().noneMatch(known -> known[0] == span[0] && known[1] == span[1])) {
                found.add(span);
            }
        }
    }

    /** Chữ thường theo từng điểm mã (không dùng {@code String.toLowerCase}) để số ký tự, nên chỉ số khớp, không đổi. */
    private static String lowerKeepingLength(String nfc) {
        StringBuilder out = new StringBuilder(nfc.length());
        nfc.codePoints().forEach(codePoint -> out.appendCodePoint(Character.toLowerCase(codePoint)));
        return out.toString();
    }

    /** Bỏ dấu theo từng ký tự (NFC cho đúng một ký tự gốc), giữ nguyên số ký tự; {@code đ} thành {@code d}. */
    private static String stripKeepingLength(String nfc) {
        StringBuilder out = new StringBuilder(nfc.length());
        for (int i = 0; i < nfc.length(); i++) {
            char c = nfc.charAt(i);
            String base = c == 'đ' ? "d"
                    : MARKS.matcher(Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFD)).replaceAll("");
            out.append(base.length() == 1 ? base.charAt(0) : c);
        }
        return out.toString();
    }
}
