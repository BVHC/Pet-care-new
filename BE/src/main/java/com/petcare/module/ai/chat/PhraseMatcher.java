package com.petcare.module.ai.chat;

import java.text.Normalizer;
import java.util.Collection;
import java.util.Locale;
import java.util.regex.Pattern;

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
        String t = norm(text);
        String p = norm(phrase);
        if (bounded(p).matcher(t).find()) {
            return true;
        }
        return p.indexOf(' ') >= 0 && bounded(strip(p)).matcher(strip(t)).find();
    }

    static boolean anyPhrase(String text, Collection<String> phrases) {
        return phrases.stream().anyMatch(phrase -> hasPhrase(text, phrase));
    }

    private static Pattern bounded(String phrase) {
        return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(phrase) + "(?![\\p{L}\\p{N}])");
    }
}
