package com.petcare.platform.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Đánh dấu tham số của method {@link Auditable} mang {@code storeId} của resource
 * BỊ TÁC ĐỘNG (không phải của actor) — ghi vào {@code audit_logs.store_id}.
 * Cùng lý do với {@link AuditOrganizationId}, xem javadoc ở đó.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface AuditStoreId {
}
