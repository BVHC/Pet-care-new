package com.petcare.module.organization.dto;

import com.petcare.platform.enums.SecurityFrameworkLevel;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * docs/api/org-store-v1.md — PATCH /organizations/{id}/policy (RULE-03-09). Partial update: field
 * null/absent = giữ nguyên (cùng convention UpdateStoreRequest). {@code version} bắt buộc để check
 * optimistic-lock — {@code 0} khi Organization chưa từng cấu hình (khớp giá trị mặc định GET trả
 * về và giá trị Hibernate @Version gán lúc insert đầu tiên).
 */
public record UpdateOrganizationPolicyRequest(
        @Min(0) Integer refundWindowDays,
        Boolean refundRequiresApproval,
        @Min(0) Integer dataRetentionDays,
        SecurityFrameworkLevel securityFrameworkLevel,
        @NotNull Long version
) {
}
