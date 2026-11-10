package com.petcare.module.ai.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Hậu kiểm liều thuốc theo ngữ cảnh (BR-AI-06), lưới phụ sau lớp {@code inScope} (ADR-0122): câu trả lời bị thay bằng
 * câu mẫu từ chối khi (a) có số kèm đơn vị liều <b>mạnh</b> (mg, mcg, µg, IU / UI, viên, ống) hoặc (b) có số kèm ml hoặc
 * giọt <b>trong cùng câu</b> với một từ thuốc. Số kèm ml ở câu về nước hoặc thức ăn, số kèm giọt ở câu về dung dịch vệ
 * sinh tai, mắt không bị chặn. Là heuristic, đo ở T56 (07 §14.1); hoạt chất lấy từ {@code ai/ai-dose-actives.txt}.
 */
@Component
class DoseGuard {

    private static final String NUMBER = "\\d+(?:[.,]\\d+)?(?:\\s*(?:-|–|đến)\\s*\\d+(?:[.,]\\d+)?)?";
    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final Pattern STRONG_UNIT = Pattern.compile(
            NUMBER + "\\s*(?:mg|mcg|µg|μg|iu|ui|viên|ống)(?![\\p{L}\\p{N}])", FLAGS);
    private static final Pattern WEAK_UNIT = Pattern.compile(NUMBER + "\\s*(?:ml|giọt)(?![\\p{L}\\p{N}])", FLAGS);
    private static final Pattern SENTENCE_BREAK = Pattern.compile("(?<=[.!?])\\s+|\\n+");
    private static final List<String> DRUG_WORDS = List.of("thuốc", "kháng sinh", "siro", "vaccine", "vắc xin",
            "tẩy giun", "xổ giun");

    private final List<String> drugWords;

    DoseGuard() {
        List<String> words = new ArrayList<>(DRUG_WORDS);
        words.addAll(ResourceLines.read("ai/ai-dose-actives.txt"));
        this.drugWords = List.copyOf(words);
    }

    boolean violates(String answer) {
        for (String sentence : SENTENCE_BREAK.split(answer)) {
            if (STRONG_UNIT.matcher(sentence).find()) {
                return true;
            }
            if (WEAK_UNIT.matcher(sentence).find() && PhraseMatcher.anyPhrase(sentence, drugWords)) {
                return true;
            }
        }
        return false;
    }
}
