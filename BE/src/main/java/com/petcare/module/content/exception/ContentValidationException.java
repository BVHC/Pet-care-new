package com.petcare.module.content.exception;

import com.petcare.platform.exception.ErrorCode;
import com.petcare.platform.exception.PlatformException;

/**
 * Dữ liệu content không hợp lệ mà hợp đồng content-v1 trả 400 {@code VALIDATION_FAILED} và không có mã {@code BR-*}
 * (ví dụ nội dung trang không phải JSON object, quá 64 KB). Bean Validation không kiểm được các điều kiện này.
 */
public class ContentValidationException extends PlatformException {

    public ContentValidationException(String message) {
        super(ErrorCode.VALIDATION_FAILED, message);
    }
}
