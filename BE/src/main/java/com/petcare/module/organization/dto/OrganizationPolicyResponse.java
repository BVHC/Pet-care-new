package com.petcare.module.organization.dto;

import com.petcare.platform.enums.SecurityFrameworkLevel;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * docs/api/openapi/org-store-v1.yaml (nửa Organization của Q7) — GET/PATCH
 * /organizations/{id}/policy. Không expose PK riêng của OrganizationPolicy — quan hệ 1:1,
 * organizationId đủ định danh. {@code updatedAt=null} khi Organization chưa từng cấu hình
 * (response dựng mặc định RULE-03-09, chưa có row nào được ghi).
 */
public record OrganizationPolicyResponse(
        UUID organizationId,
        int refundWindowDays,
        boolean refundRequiresApproval,
        int dataRetentionDays,
        SecurityFrameworkLevel securityFrameworkLevel,
        long version,
        LocalDateTime updatedAt
) {
}
