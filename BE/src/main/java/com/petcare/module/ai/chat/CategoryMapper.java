package com.petcare.module.ai.chat;

import com.petcare.module.ai.entity.ChatIntent;
import com.petcare.module.ai.llm.AnswerCategory;

/**
 * Bảng ánh xạ {@code category} của chế độ mở thành quyết định và ý định ghi nhật ký (docs/adr/0136 D2, "Đã cài ở T92 (phần 2)").
 * Chỉ {@code PET_CARE} và {@code OUT_OF_DOMAIN} được trả lời; mọi giá trị khác, kể cả thiếu hoặc lạ ({@code null}), là từ chối.
 */
final class CategoryMapper {

    enum Verdict {
        /** Chăm sóc thú cưng, có đoạn tài liệu đạt τ làm căn cứ. */
        KNOWLEDGE,
        /** Chăm sóc thú cưng, không có tài liệu: kiến thức chung kèm tiền tố ngắn. */
        GENERAL,
        /** Ngoài lĩnh vực thú cưng nhưng vô hại: kiến thức chung kèm tiền tố riêng, không nguồn. */
        OPEN,
        REFUSE_MEDICAL,
        REFUSE_SCOPE
    }

    record Decision(Verdict verdict, ChatIntent intent) {
    }

    private CategoryMapper() {
    }

    static Decision decide(AnswerCategory category, boolean hasHits) {
        if (category == null) {
            return new Decision(Verdict.REFUSE_SCOPE, ChatIntent.UNKNOWN);
        }
        return switch (category) {
            case PET_CARE -> new Decision(hasHits ? Verdict.KNOWLEDGE : Verdict.GENERAL, ChatIntent.PET_CARE);
            case OUT_OF_DOMAIN -> new Decision(Verdict.OPEN, ChatIntent.OUT_OF_DOMAIN);
            case MEDICAL_RISK -> new Decision(Verdict.REFUSE_MEDICAL, ChatIntent.MEDICAL_RISK);
            case BLOCKED -> new Decision(Verdict.REFUSE_SCOPE, ChatIntent.OUT_OF_DOMAIN);
            case BUSINESS -> new Decision(Verdict.REFUSE_SCOPE, ChatIntent.UNKNOWN);
        };
    }
}
