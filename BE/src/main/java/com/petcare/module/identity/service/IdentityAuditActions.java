package com.petcare.module.identity.service;

/**
 * Mã action audit của module identity (convention 03: {@code <ĐỐI_TƯỢNG>_<QUÁ_KHỨ>}; danh mục ở convention 08 §8.3).
 * Lý do ({@code audit_logs.reason}) của {@code LOGIN_FAILED} theo docs/adr/0019 mục 6.
 */
public final class IdentityAuditActions {

    public static final String LOGIN_SUCCEEDED = "LOGIN_SUCCEEDED";
    public static final String LOGIN_FAILED = "LOGIN_FAILED";

    /** {@code LOGIN_FAILED.reason}: email không khớp tài khoản nào (kể cả vừa bị ST02 xóa). */
    public static final String REASON_UNKNOWN_EMAIL = "UNKNOWN_EMAIL";
    /** {@code LOGIN_FAILED.reason}: sai mật khẩu (cả mật khẩu &gt; 72 byte), tính vào bộ đếm BR-TK-09. */
    public static final String REASON_BAD_CREDENTIALS = "BAD_CREDENTIALS";
    /** {@code LOGIN_FAILED.reason}: đang khóa tạm (BR-TK-09) — sai thì không đếm, đúng thì 400. */
    public static final String REASON_TEMPORARILY_LOCKED = "TEMPORARILY_LOCKED";
    /** {@code LOGIN_FAILED.reason}: mật khẩu đúng, tài khoản {@code PENDING} (BR-TK-08). */
    public static final String REASON_PENDING = "PENDING";
    /** {@code LOGIN_FAILED.reason}: mật khẩu đúng, tài khoản bị khóa {@code is_locked} (BR-TK-11). */
    public static final String REASON_LOCKED = "LOCKED";
    /** {@code LOGIN_FAILED.reason}: mật khẩu đúng, tài khoản {@code DISABLED} (BR-TK-11). */
    public static final String REASON_DISABLED = "DISABLED";

    private IdentityAuditActions() {
    }
}
