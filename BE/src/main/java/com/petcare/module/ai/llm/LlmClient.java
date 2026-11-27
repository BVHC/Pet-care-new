package com.petcare.module.ai.llm;

import java.util.List;

/**
 * Mô hình ngôn ngữ trả JSON {@code {inScope, answer}} (07 §5.2 bước 8, docs/adr/0109 D1) hoặc, ở chế độ mở, {@code {category,
 * answer}} (docs/adr/0136 D2). Lỗi ném {@link AiProviderException}.
 */
public interface LlmClient {

    /** Tên mô hình, ghi vào {@code ai_chat_logs.llm_model}. */
    String llmModel();

    /** {@code systemPrompt} là lời nhắc §5.4; {@code userBlock} là khối tài liệu + câu hỏi (chỉ đi vào vai người dùng). */
    LlmAnswer generate(String systemPrompt, String userBlock);

    /** Như {@link #generate} nhưng đầu ra là {@code {category, answer}}; {@code category} {@code null} nếu giá trị lạ. */
    LlmAnswer generateCategorized(String systemPrompt, String userBlock);

    /**
     * Phân loại: mô hình chỉ được trả một phần tử của {@code labels} (lược đồ enum, docs/adr/0157). Nhãn thiếu, không phải
     * chuỗi hoặc ngoài {@code labels} là {@link AiProviderException}.
     */
    String classify(String systemPrompt, String userBlock, List<String> labels);
}
