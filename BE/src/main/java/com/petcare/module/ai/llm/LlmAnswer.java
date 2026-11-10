package com.petcare.module.ai.llm;

/**
 * Kết quả một lệnh sinh (07 §5.2 bước 8): JSON {@code {inScope, answer}} cùng số token để ghi nhật ký (BR-AI-24). Token
 * là {@code null} khi nhà cung cấp không báo.
 */
public record LlmAnswer(boolean inScope, String answer, Integer inputTokens, Integer outputTokens,
        Integer thoughtTokens) {
}
