package com.petcare.module.identity.exception;

import com.petcare.platform.exception.ErrorCode;
import com.petcare.platform.exception.PlatformException;

/**
 * Lễ tân chưa sửa được email tài khoản của hồ sơ khách này (identity-v1 #25, #26; UC22): hồ sơ chưa gắn tài khoản online,
 * hoặc tài khoản còn {@code PENDING} (chưa xác thực email, BR-TK-08). Không thuộc rule {@code BR-*} nào nên 400
 * {@code VALIDATION_FAILED} với message không kèm mã rule, đúng hợp đồng — khác {@code BusinessRuleViolationException}
 * vốn luôn nối {@code " (BR-…)"} vào message. Lớp riêng theo tiêu chí 2 của convention 04 §4.2 (message trả client khác
 * default của lỗi rule), cùng khuôn {@code BranchSelectionRequiredException}.
 */
public class CustomerAccountNotEditableException extends PlatformException {

    public CustomerAccountNotEditableException(String message) {
        super(ErrorCode.VALIDATION_FAILED, message);
    }
}
