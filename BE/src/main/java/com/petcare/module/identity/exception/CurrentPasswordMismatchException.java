package com.petcare.module.identity.exception;

import com.petcare.platform.exception.BusinessRuleViolationException;

/**
 * Đổi mật khẩu (UC05): mật khẩu hiện tại sai — 400 {@code BR-TK-14} như mọi {@link BusinessRuleViolationException},
 * nhưng lần sai phải được tính vào bộ đếm BR-TK-09 dù request lỗi (docs/adr/0022). Lớp riêng theo tiêu chí 3 của
 * convention 04 §4.2 (logic rollback riêng): transaction đổi mật khẩu khai báo
 * {@code noRollbackFor = CurrentPasswordMismatchException.class} nên bộ đếm, {@code locked_until}, email cảnh báo và
 * audit được commit — cùng cách {@link OtpRejectedException} (docs/adr/0010), {@link InvalidCredentialsException}
 * (docs/adr/0019).
 * <p>
 * Bất biến: chỉ ném trực tiếp trong method có {@code noRollbackFor}, sau mọi lệnh ghi của nhánh đó; không bao giờ ném
 * từ bên trong một bean {@code @Transactional} khác (sẽ đánh dấu rollback-only → 500).
 */
public class CurrentPasswordMismatchException extends BusinessRuleViolationException {

    public static final String MESSAGE = "Mật khẩu hiện tại không đúng";

    public CurrentPasswordMismatchException(String message) {
        super("BR-TK-14", message);
    }
}
