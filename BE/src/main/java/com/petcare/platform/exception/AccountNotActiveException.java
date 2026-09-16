package com.petcare.platform.exception;

import com.petcare.platform.enums.AccountStatus;

/**
 * RULE-01-01 — Login/Refresh bị từ chối vì Account không ở trạng thái
 * ACTIVE (PENDING_VERIFICATION chưa xác thực OTP, hoặc DEACTIVATED). HTTP
 * 403 — khác 5 exception nền nên tách riêng theo tiêu chí §4.2.2
 * (docs/convention/backend/04-exception-handling.md). Không dùng cho LOCKED
 * — trường hợp đó có exception riêng (AccountLockedException) vì mang thêm
 * `lockedUntil` và HTTP status khác (423).
 */
public class AccountNotActiveException extends BusinessRuleViolationException {

    public AccountNotActiveException(AccountStatus status) {
        super("RULE-01-01", buildMessage(status));
    }

    private static String buildMessage(AccountStatus status) {
        return switch (status) {
            case PENDING_VERIFICATION -> "Tài khoản chưa xác thực OTP, vui lòng xác thực trước khi đăng nhập";
            case DEACTIVATED -> "Tài khoản đã bị vô hiệu hóa";
            default -> "Tài khoản không ở trạng thái hoạt động";
        };
    }
}
