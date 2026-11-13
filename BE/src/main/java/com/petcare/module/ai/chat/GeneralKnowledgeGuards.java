package com.petcare.module.ai.chat;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Hai lớp tất định chạy <b>trước</b> embedding và LLM, chỉ khi [CFG] {@code ai.chat.general_knowledge_enabled} bật
 * (docs/adr/0136 D2): lời chào thuần túy trả câu mẫu thân thiện; cụm từ rủi ro y khoa ("bị bệnh gì", "uống thuốc gì",
 * "liều thuốc"...) trả {@code MEDICAL_REFUSAL}, không phụ thuộc cờ {@code inScope} do mô hình tự đặt (Q24 ngày 12/11/2026:
 * quyết định giữ nguyên chỉ còn đúng khi công tắc tắt; Hiến đồng ý 13/11 lớp này chỉ chạy khi bật).
 */
final class GeneralKnowledgeGuards {

    private static final List<String> MEDICAL_RISK = ResourceLines.read("ai/ai-medical-risk-keywords.txt");
    /** Cả câu chỉ là lời chào hoặc cảm ơn, có thể kèm "bạn", "ạ", "nhé"...; câu có nội dung hỏi thêm thì không khớp. */
    private static final Pattern GREETING = Pattern.compile(
            "^(xin chao|chao|hello|hi|hey|alo|cam on|thanks|thank you)( ban| a| nhe| nha| tro ly| ad| bot| pet care)*$");

    private GeneralKnowledgeGuards() {
    }

    static boolean isGreeting(String question) {
        String plain = Normalizer.normalize(question.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replace('đ', 'd').replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").strip();
        return GREETING.matcher(plain).matches();
    }

    static boolean isMedicalRisk(String question) {
        return PhraseMatcher.anyPhrase(question, MEDICAL_RISK);
    }
}
