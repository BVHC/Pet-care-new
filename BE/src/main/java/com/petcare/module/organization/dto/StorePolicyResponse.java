package com.petcare.module.organization.dto;

import com.petcare.platform.enums.SurchargeType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * docs/api/openapi/org-store-v1.yaml — GET/PATCH /stores/{id}/policy. Không expose PK riêng của
 * StorePolicy — quan hệ 1:1, storeId đủ định danh. {@code surchargeType}/{@code surchargeValue}
 * đều null khi {@code surchargeEnabled=false} (RULE-03-10). {@code updatedAt=null} khi Store chưa
 * từng cấu hình (response dựng mặc định, chưa có row nào được ghi). {@code organizationId} (Org
 * cha của Store) tồn tại chỉ để {@link com.petcare.platform.audit.AuditAspect} dò accessor và ghi
 * đúng {@code audit_logs.organization_id} của resource bị tác động — không phải actor (xem
 * AuditAspect javadoc §ưu tiên 2) — không phải field nghiệp vụ cho client tiêu thụ.
 */
public record StorePolicyResponse(
        UUID storeId,
        UUID organizationId,
        boolean surchargeEnabled,
        SurchargeType surchargeType,
        BigDecimal surchargeValue,
        long version,
        LocalDateTime updatedAt
) {
}
