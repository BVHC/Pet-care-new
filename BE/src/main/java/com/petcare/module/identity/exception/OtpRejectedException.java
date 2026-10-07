package com.petcare.module.identity.exception;

import com.petcare.platform.exception.BusinessRuleViolationException;

/**
 * Mã OTP bị từ chối khi xác thực (BR-TK-05 sai / hết hạn, BR-TK-06 sai quá số lần). Lớp riêng theo tiêu chí 3 của
 * convention 04 §4.2: cần cách rollback riêng — lỗi này phải <b>commit</b> lệnh tăng {@code failed_attempts} và
 * {@code invalidated_at}, nên mọi {@code @Transactional} mà nó đi qua khai báo
 * {@code noRollbackFor = OtpRejectedException.class} (docs/adr/0010). Chỉ ném khi lệnh ghi duy nhất của transaction
 * là bộ đếm trên chính dòng OTP. Client nhận như mọi {@link BusinessRuleViolationException}: 400.
 */
public class OtpRejectedException extends BusinessRuleViolationException {

    public OtpRejectedException(String ruleId, String message) {
        super(ruleId, message);
    }
}
