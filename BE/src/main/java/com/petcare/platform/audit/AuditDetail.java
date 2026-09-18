package com.petcare.platform.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Đánh dấu tường minh một tham số kiểu scalar (String/UUID/enum...) của method
 * {@link Auditable} là an toàn để ghi vào {@code audit_logs.snapshot_after}
 * (vd {@code reason} trong {@code reactivateAccount(accountId, reason)} —
 * RULE-02-07 bắt buộc lý do giải trình phải vào Audit Log).
 * <p>
 * Cơ chế opt-in có chủ đích: {@link AuditAspect} KHÔNG tự động log toàn bộ
 * tham số thô của method (một số method @Auditable như {@code createStaff}
 * nhận request chứa mật khẩu tạm) — chỉ field nào được đánh dấu rõ ràng mới
 * lọt vào snapshot.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface AuditDetail {

    /** Tên field trong JSON snapshot, vd "reason". */
    String value();
}
