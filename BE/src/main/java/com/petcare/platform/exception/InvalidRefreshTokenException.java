package com.petcare.platform.exception;

/**
 * RULE-01-06 — refresh token không hợp lệ: sai loại token (không phải
 * `type=refresh`), JWT malformed/hết hạn, hoặc không tìm thấy/đã bị revoke
 * trong `refresh_tokens` (rotate-reuse). HTTP 401 — khác 5 exception nền nên
 * tách riêng theo tiêu chí §4.2.2 (docs/convention/backend/04-exception-handling.md).
 */
public class InvalidRefreshTokenException extends BusinessRuleViolationException {

    public InvalidRefreshTokenException() {
        super("RULE-01-06", "Refresh token không hợp lệ hoặc đã hết hiệu lực");
    }
}
