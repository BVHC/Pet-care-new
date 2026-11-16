package com.petcare.module.ai.entity;

/**
 * {@code ai_chat_logs.intent} (docs/adr/0136 D4, V57): ý định của lượt hỏi, chỉ là nhãn cho thống kê, không chứa nội dung câu
 * hỏi (BR-AI-24). {@link #UNKNOWN} cho lời chào, câu không phân loại được, lỗi nhà cung cấp và câu bị chặn không rõ nhóm.
 */
public enum ChatIntent {
    EMERGENCY,
    MEDICAL_RECORD,
    APPOINTMENT_UPCOMING,
    VACCINATION_SCHEDULE,
    BUSINESS_PRICE,
    BUSINESS_BRANCH,
    PET_CARE,
    MEDICAL_RISK,
    OUT_OF_DOMAIN,
    UNKNOWN
}
