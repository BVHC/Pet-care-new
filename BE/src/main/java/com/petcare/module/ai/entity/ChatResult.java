package com.petcare.module.ai.entity;

/** {@code ai_chat_logs.result} và {@code ChatResponse.result} của ai-v1 (07 §10): kết quả một lượt hỏi đáp. */
public enum ChatResult {
    ANSWERED,
    OUT_OF_SCOPE,
    MEDICAL_REFUSAL,
    EMERGENCY,
    UNAVAILABLE,
    PERSONAL,
    NEED_PET_CHOICE,
    NEED_LOGIN
}
