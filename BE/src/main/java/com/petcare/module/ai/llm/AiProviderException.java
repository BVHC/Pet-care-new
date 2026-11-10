package com.petcare.module.ai.llm;

import com.petcare.module.ai.entity.ChatErrorKind;

/**
 * Nhà cung cấp AI không trả lời được (BR-AI-10). Lớp client chỉ <b>phân loại</b> lỗi; bên gọi quyết định thử lại hay
 * không (chat: không thử lại trong cùng request; ST22: thử lại có giới hạn khi 429, 07 §5.3). Message không bao giờ
 * chứa khóa API.
 */
public class AiProviderException extends RuntimeException {

    private final ChatErrorKind kind;

    public AiProviderException(ChatErrorKind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public AiProviderException(ChatErrorKind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public ChatErrorKind kind() {
        return kind;
    }
}
