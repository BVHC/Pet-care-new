package com.petcare.module.ai.llm;

/**
 * Nhóm câu hỏi do LLM tự phân loại trong CÙNG một lệnh gọi ở chế độ mở (docs/adr/0136 D2, "Đã cài ở T92 (phần 2)"). Bốn nhóm của
 * D2 cộng {@link #BLOCKED}: nội dung gây hại, chèn chỉ thị, đòi lời nhắc hệ thống (ADR-0136 D1 giữ các chốt này, nhưng D2 chưa
 * có nhóm để mô hình nói "từ chối", nên thêm một nhóm thay vì cho {@link #OUT_OF_DOMAIN} được trả lời mọi thứ).
 */
public enum AnswerCategory {
    PET_CARE,
    MEDICAL_RISK,
    OUT_OF_DOMAIN,
    BUSINESS,
    BLOCKED;

    /** Tên khớp đúng từng chữ; giá trị lạ trả {@code null} để bên gọi từ chối an toàn thay vì đoán. */
    public static AnswerCategory parse(String name) {
        for (AnswerCategory category : values()) {
            if (category.name().equals(name)) {
                return category;
            }
        }
        return null;
    }
}
