package com.petcare.module.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.Positive;

/**
 * Body của {@code POST /api/staff/{id}/reactivate} (identity-v1 {@code ReactivateStaffRequest}, UC08 — docs/adr/0033).
 * Không bắt buộc: thiếu body, {@code {}} hoặc {@code branchId} null = giữ chi nhánh hiện tại (BR-QT-10 "có thể gán lại
 * chi nhánh"). Chỉ kiểm hình thức (convention 06); chi nhánh tồn tại, SUPER_MANAGER không có chi nhánh (BR-QT-03) kiểm ở
 * service. Số thực bị Jackson từ chối → 400 {@code MALFORMED_REQUEST} (docs/adr/0032).
 */
public record ReactivateStaffRequest(
        @Schema(description = "Chi nhánh mới (BR-QT-03); bỏ trống = giữ chi nhánh cũ", example = "2")
        @Positive(message = "branchId phải là số dương")
        Long branchId) {
}
