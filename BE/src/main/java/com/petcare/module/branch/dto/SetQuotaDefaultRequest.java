package com.petcare.module.branch.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Body của {@code PUT /api/branches/{id}/quota-defaults/{serviceGroup}} (branch-v1 {@code SetQuotaDefaultRequest},
 * UC42). Khoảng giá trị (0…32767, cột {@code SMALLINT}) kiểm ở service để trả {@code BR-LH-03}.
 */
public record SetQuotaDefaultRequest(
        @NotNull(message = "Thiếu quota mặc định")
        Integer defaultQuota) {
}
