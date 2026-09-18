package com.petcare.platform.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Đánh dấu tham số của method {@link Auditable} mang {@code organizationId} của
 * resource BỊ TÁC ĐỘNG (không phải của actor) — để {@link AuditAspect} ghi đúng
 * {@code audit_logs.organization_id} theo RULE-25-01 (`scope_id`).
 * <p>
 * Không có annotation này, {@link AuditAspect} mặc định lấy {@code organizationId}
 * từ actor (`UserPrincipal`) — ĐÚNG khi actor là `ORGANIZATION_ADMIN` thao tác
 * trong chính Org của mình, nhưng SAI khi actor là `SUPER_ADMIN` (luôn có
 * `organizationId = null` theo {@code RoleScopeGuard.validateRoleScopeBinding})
 * thao tác trên tài khoản thuộc 1 Organization cụ thể — audit_logs sẽ ghi
 * `organization_id = NULL` thay vì Organization thật sự bị tác động, khiến
 * Organization Admin tra cứu Audit Log theo phạm vi Organization
 * (`RULE-25-07`) không thấy được hành động đó.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface AuditOrganizationId {
}
