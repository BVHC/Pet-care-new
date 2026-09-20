package com.petcare.platform.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Đánh dấu tham số của method {@link Auditable} mang ID của resource bị tác động
 * (vd {@code accountId} trong {@code lockAccount(UUID accountId)}), để
 * {@link AuditAspect} biết chính xác nên ghi giá trị nào vào cột
 * {@code audit_logs.resource_id} — không đoán theo vị trí/tên tham số vì chữ ký
 * mỗi method @Auditable khác nhau. Nếu method không có tham số nào đánh dấu
 * (vd lệnh tạo mới — resource chưa tồn tại lúc vào method), {@link AuditAspect}
 * tự dò các accessor phổ biến (id/accountId/organizationId/userId) trên giá trị
 * trả về sau khi method chạy xong.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface AuditResourceId {
}
