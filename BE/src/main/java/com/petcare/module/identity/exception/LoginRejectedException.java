package com.petcare.module.identity.exception;

import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.exception.BusinessRuleViolationException;

/**
 * Mật khẩu đúng nhưng tài khoản không được đăng nhập: BR-TK-08 ({@code PENDING}), BR-TK-09 (đang khóa tạm), BR-TK-11
 * (bị khóa / vô hiệu hóa). Client nhận như mọi {@link BusinessRuleViolationException} (400, mã rule cuối message).
 * Lớp riêng theo tiêu chí 3 của convention 04 §4.2 (logic rollback riêng, docs/adr/0019 mục 6): transaction đăng nhập
 * rollback sạch (chưa ghi gì), rồi {@code LoginService} — ngoài transaction, khóa dòng đã nhả, connection đã trả —
 * ghi {@link #auditEntry()} bằng {@code AuditRecorder.recordIndependently}. Ghi ngay trong transaction đang giữ khóa
 * thì cần connection thứ hai và có thể kẹt pool.
 */
public class LoginRejectedException extends BusinessRuleViolationException {

    private final transient AuditEntry auditEntry;

    public LoginRejectedException(String ruleId, String message, AuditEntry auditEntry) {
        super(ruleId, message);
        this.auditEntry = auditEntry;
    }

    public AuditEntry auditEntry() {
        return auditEntry;
    }
}
