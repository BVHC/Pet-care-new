package com.petcare.platform.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * docs/convention/backend/08-logging-and-audit.md — đánh dấu method Service
 * thực thi 1 Command nghiệp vụ nhạy cảm (Maker-Checker, thay đổi quyền/trạng
 * thái tài khoản...) cần audit log bắt buộc. {@link AuditAspect} đọc
 * annotation này để ghi log có cấu trúc, không cần Service tự viết log audit
 * thủ công ở từng nơi.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Auditable {

    /** Tên Command nghiệp vụ, khớp chính xác Ubiquitous Language (docs/04-glossary.md). */
    String action();

    /** Loại resource bị tác động, vd "Account"/"User"/"Organization" — ghi vào audit_logs.resource_type. */
    String resourceType();
}
