package com.petcare.module.organization.dto;

import com.petcare.platform.enums.SurchargeType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * docs/api/org-store-v1.md — PATCH /stores/{id}/policy (RULE-03-10). Partial update: field
 * null/absent = giữ nguyên (cùng convention UpdateOrganizationPolicyRequest). {@code version} bắt
 * buộc để check optimistic-lock — {@code 0} khi Store chưa từng cấu hình. Ràng buộc chéo
 * (bắt buộc surchargeType/surchargeValue khi surchargeEnabled=true; 0-100 khi PERCENTAGE) được
 * validate ở service trên trạng thái CUỐI CÙNG sau khi áp dụng partial update, không ở đây.
 */
public record UpdateStorePolicyRequest(
        Boolean surchargeEnabled,
        SurchargeType surchargeType,
        @DecimalMin(value = "0", message = "surchargeValue phải >= 0") BigDecimal surchargeValue,
        @NotNull Long version
) {
}
