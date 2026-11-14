package com.petcare.module.ai.chat;

import java.util.List;
import java.util.Map;

/**
 * Nhận ý định nghiệp vụ bằng từ khóa (BR-AI-31, docs/adr/0134 "Đã cài ở T90"): hỏi giá dịch vụ hoặc hỏi chi nhánh. Chỉ nhận
 * ý định, không trả lời: {@link BusinessAnswerService} dựng câu từ CSDL. Chạy ở cả hai endpoint, sau cấp cứu, bệnh án và ý
 * định cá nhân, trước hai lớp T92 và trước truy hồi.
 */
final class BusinessIntents {

    private static final String PRICE = "PRICE";
    private static final String PRICE_WEAK = "PRICE_WEAK";
    private static final String PRICE_EXCLUDE = "PRICE_EXCLUDE";
    private static final String BRANCH = "BRANCH";

    private static final Map<String, List<String>> KEYWORDS = ResourceLines.readSections("ai/ai-business-keywords.txt",
            List.of(PRICE, PRICE_WEAK, PRICE_EXCLUDE, BRANCH));

    private BusinessIntents() {
    }

    /** Hỏi giá bằng một cụm đủ nghĩa ("giá", "bao nhiêu tiền", "chi phí"...), sau khi che cụm như "giá trị". */
    static boolean isPrice(String question) {
        return PhraseMatcher.anyPhrase(withoutExcluded(question), KEYWORDS.get(PRICE));
    }

    /** Chỉ có từ "phí" (không đủ để coi là hỏi giá): bên gọi phải thấy thêm một tên dịch vụ trong câu. */
    static boolean mentionsWeakPrice(String question) {
        return PhraseMatcher.anyPhrase(withoutExcluded(question), KEYWORDS.get(PRICE_WEAK));
    }

    static boolean isBranch(String question) {
        return PhraseMatcher.anyPhrase(question, KEYWORDS.get(BRANCH));
    }

    /** Thay cụm loại trừ bằng khoảng trắng cùng độ dài (vị trí của {@link PhraseMatcher#spans} theo NFC của câu hỏi). */
    private static String withoutExcluded(String question) {
        StringBuilder text = new StringBuilder(PhraseMatcher.nfc(question));
        for (String phrase : KEYWORDS.get(PRICE_EXCLUDE)) {
            for (int[] span : PhraseMatcher.spans(question, phrase)) {
                for (int i = span[0]; i < span[1]; i++) {
                    text.setCharAt(i, ' ');
                }
            }
        }
        return text.toString();
    }
}
