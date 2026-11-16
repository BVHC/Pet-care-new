package com.petcare.module.ai.llm;

/**
 * Kết quả một lệnh sinh (07 §5.2 bước 8): JSON {@code {inScope, answer}} (lược đồ cũ) hoặc {@code {category, answer}} (chế độ
 * mở, {@link LlmClient#generateCategorized}) cùng số token để ghi nhật ký (BR-AI-24). Token là {@code null} khi nhà cung cấp
 * không báo. {@code category} chỉ có ở lược đồ mới và là {@code null} khi mô hình trả giá trị lạ; khi đó {@code inScope} suy
 * từ nhóm (true chỉ với {@code PET_CARE}, {@code OUT_OF_DOMAIN}).
 */
public record LlmAnswer(boolean inScope, String answer, Integer inputTokens, Integer outputTokens, Integer thoughtTokens,
        AnswerCategory category) {

    public LlmAnswer(boolean inScope, String answer, Integer inputTokens, Integer outputTokens, Integer thoughtTokens) {
        this(inScope, answer, inputTokens, outputTokens, thoughtTokens, null);
    }
}
