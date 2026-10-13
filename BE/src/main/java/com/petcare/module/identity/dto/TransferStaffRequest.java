package com.petcare.module.identity.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Body của {@code POST /api/staff/{id}/transfer} (identity-v1 {@code TransferStaffRequest}, UC08 — docs/adr/0031). Chỉ
 * kiểm hình thức (convention 06); chi nhánh tồn tại (BR-QT-03), BR-QT-04, BR-QT-08 kiểm ở service. Số thực (ví dụ
 * {@code 1.9}) bị Jackson từ chối → 400 {@code MALFORMED_REQUEST} (docs/adr/0032), không bị cắt thành số nguyên.
 */
public record TransferStaffRequest(
        @NotNull(message = "Vui lòng chọn chi nhánh")
        @Positive(message = "branchId phải là số dương")
        Long branchId) {
}
