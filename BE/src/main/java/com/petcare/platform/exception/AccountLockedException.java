package com.petcare.platform.exception;

import java.time.LocalDateTime;

/**
 * RULE-01-07 — tài khoản đang ở trạng thái LOCKED (AutoLockAccount hoặc
 * ADMIN_LOCK) tại thời điểm Login/Refresh. HTTP 423 — khác 5 exception nền
 * nên tách riêng theo tiêu chí §4.2.2 (docs/convention/backend/04-exception-handling.md).
 * `lockedUntil` nhúng thẳng vào message thay vì thêm field mới vào
 * ErrorResponse — giữ nguyên envelope 6 field dùng chung toàn hệ thống
 * (§4.3); null khi khóa không có hạn tự mở (ADMIN_LOCK).
 */
public class AccountLockedException extends BusinessRuleViolationException {

    public AccountLockedException(LocalDateTime lockedUntil) {
        super("RULE-01-07", buildMessage(lockedUntil));
    }

    private static String buildMessage(LocalDateTime lockedUntil) {
        if (lockedUntil == null) {
            return "Tài khoản đang bị khóa, vui lòng liên hệ quản trị viên để được mở khóa";
        }
        return "Tài khoản đang tạm khóa do nhập sai mật khẩu quá số lần cho phép, mở khóa lúc " + lockedUntil;
    }
}
