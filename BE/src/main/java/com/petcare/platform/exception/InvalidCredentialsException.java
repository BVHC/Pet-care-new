package com.petcare.platform.exception;

/**
 * RULE-01-01 — email không tồn tại hoặc sai mật khẩu. Message luôn chung
 * chung (không phân biệt 2 nguyên nhân) để chống dò email đang tồn tại
 * (docs/api/auth-v1.md §D). HTTP 401 — khác 5 exception nền nên tách riêng
 * theo tiêu chí §4.2.2 (docs/convention/backend/04-exception-handling.md).
 */
public class InvalidCredentialsException extends BusinessRuleViolationException {

    public InvalidCredentialsException() {
        super("RULE-01-01", "Email hoặc mật khẩu không đúng");
    }
}
