package com.petcare.module.identity.service;

/**
 * Mã action audit của module identity (convention 03: {@code <ĐỐI_TƯỢNG>_<QUÁ_KHỨ>}; danh mục ở convention 08 §8.3).
 * Lý do ({@code audit_logs.reason}) của {@code LOGIN_FAILED} theo docs/adr/0019 mục 6.
 */
public final class IdentityAuditActions {

    public static final String LOGIN_SUCCEEDED = "LOGIN_SUCCEEDED";
    public static final String LOGIN_FAILED = "LOGIN_FAILED";
    /**
     * Lần nhập sai mật khẩu hiện tại ở đổi mật khẩu (BR-TK-14) kích hoạt khóa tạm BR-TK-09 (docs/adr/0019 mục 6,
     * docs/adr/0022). Lần sai chưa chạm ngưỡng và đổi thành công không audit.
     */
    public static final String ACCOUNT_TEMPORARILY_LOCKED = "ACCOUNT_TEMPORARILY_LOCKED";
    /**
     * Khách tự liên kết tài khoản vào hồ sơ tại quầy bằng OTP gửi tới email hồ sơ (BR-TK-19, UC07; docs/adr/0027).
     * {@code entity = customers/<hồ sơ tại quầy>}, before/after có {@code verificationMethod}.
     */
    public static final String CUSTOMER_PROFILE_LINKED = "CUSTOMER_PROFILE_LINKED";

    /** {@code verificationMethod} của {@code CUSTOMER_PROFILE_LINKED} khi khách tự liên kết (mã gửi tới email hồ sơ). */
    public static final String VERIFICATION_EMAIL_CODE = "EMAIL_CODE";

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
