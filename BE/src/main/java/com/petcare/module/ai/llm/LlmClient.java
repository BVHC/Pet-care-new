package com.petcare.module.ai.llm;

/** Mô hình ngôn ngữ trả JSON {@code {inScope, answer}} (07 §5.2 bước 8, docs/adr/0109 D1). Lỗi ném {@link AiProviderException}. */
public interface LlmClient {

    /** Tên mô hình, ghi vào {@code ai_chat_logs.llm_model}. */
    String llmModel();

    /** {@code systemPrompt} là lời nhắc §5.4; {@code userBlock} là khối tài liệu + câu hỏi (chỉ đi vào vai người dùng). */
    LlmAnswer generate(String systemPrompt, String userBlock);
}
