package com.petcare.module.organization.dto;

import java.util.List;
import java.util.UUID;

/**
 * {@code organizationId} (Org cha của Store) tồn tại chỉ để {@link com.petcare.platform.audit.AuditAspect}
 * dò accessor và ghi đúng {@code audit_logs.organization_id} của resource bị tác động — không phải
 * actor (xem AuditAspect javadoc §ưu tiên 2) — không phải field nghiệp vụ cho client tiêu thụ.
 */
public record OperatingHoursResponse(
        UUID storeId,
        UUID organizationId,
        List<OperatingHourItem> hours
) {
}
