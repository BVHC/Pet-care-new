package com.petcare.module.ai.exception;

import com.petcare.platform.exception.ErrorCode;
import com.petcare.platform.exception.PlatformException;

/**
 * Hồ sơ gợi ý không hợp lệ ở luật chéo trường (UC94): {@code min} lớn hơn {@code max}. Bean Validation chỉ kiểm được từng
 * trường, nên luật này do {@code ItemProfileService} kiểm; hợp đồng ai-v1 #13 trả 400 {@code VALIDATION_FAILED}.
 */
public class ProfileValidationException extends PlatformException {

    public ProfileValidationException(String message) {
        super(ErrorCode.VALIDATION_FAILED, message);
    }
}
