package com.petcare.module.visit.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Body của {@code POST /api/visits/{visitId}/assign} (visit-v1 {@code AssignRequest}, UC45; docs/adr/0061). Giữ tên
 * schema của hợp đồng (docs/adr/0058 mục 3). Nhân viên có hoạt động, đúng chi nhánh, đúng chức vụ hay không (BR-TN-05)
 * kiểm ở service dưới khóa tài khoản; trường lạ bị bỏ qua.
 */
public record AssignRequest(
        @Schema(description = "VET cho MEDICAL, CARETAKER cho GROOMING (BR-TN-05)", example = "45")
        @NotNull(message = "Thiếu nhân viên được gán")
        @Positive(message = "Mã nhân viên không hợp lệ")
        Long assigneeId) {
}
