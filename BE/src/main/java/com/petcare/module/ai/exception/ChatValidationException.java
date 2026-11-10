package com.petcare.module.ai.exception;

import com.petcare.platform.exception.ErrorCode;
import com.petcare.platform.exception.PlatformException;

/**
 * Đầu vào hỏi đáp không hợp lệ (BR-AI-08): giới hạn độ dài và số câu hỏi trước đọc từ [CFG] {@code ai.chat.*} lúc xử lý
 * nên Bean Validation (hằng số lúc biên dịch) không kiểm được. Hợp đồng ai-v1 trả 400 {@code VALIDATION_FAILED}.
 */
public class ChatValidationException extends PlatformException {

    public ChatValidationException(String message) {
        super(ErrorCode.VALIDATION_FAILED, message);
    }
}
