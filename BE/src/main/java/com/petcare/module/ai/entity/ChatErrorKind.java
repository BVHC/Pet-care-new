package com.petcare.module.ai.entity;

/** {@code ai_chat_logs.error_kind} (BR-AI-10): vì sao nhà cung cấp AI không trả lời được. */
public enum ChatErrorKind {
    TIMEOUT,
    PROVIDER_RATE_LIMIT,
    PROVIDER_ERROR,
    NO_KEY
}
