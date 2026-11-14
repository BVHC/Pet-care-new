package com.petcare.module.ai.entity;

/**
 * {@code ai_chat_logs.source_type} và {@code ChatResponse.sourceType} của ai-v1 (BR-AI-34, docs/adr/0136 D4): câu trả lời
 * lấy từ đâu. {@code null} cho {@code OUT_OF_SCOPE}, {@code UNAVAILABLE} và lời chào (không có nguồn nào).
 */
public enum ChatSourceType {
    /** Câu mẫu dựng từ CSDL: lịch hẹn, lịch tiêm, giá dịch vụ, chi nhánh. */
    DATABASE,
    /** Trả lời theo tài liệu {@code PUBLISHED} đã truy hồi. */
    KNOWLEDGE_BASE,
    /** Trả lời bằng kiến thức chung của mô hình, không có tài liệu làm căn cứ (BR-AI-33). */
    GENERAL_KNOWLEDGE,
    /** Có tài liệu làm căn cứ và mô hình bổ sung thêm bằng kiến thức chung. */
    KNOWLEDGE_BASE_AND_GENERAL,
    /** Câu mẫu an toàn: cấp cứu và từ chối y khoa. */
    SAFETY
}
