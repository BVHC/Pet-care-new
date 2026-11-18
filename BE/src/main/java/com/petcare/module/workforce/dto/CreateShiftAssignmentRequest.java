package com.petcare.module.workforce.dto;

import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * Phân ca hộ (workforce-v1 {@code CreateShiftAssignmentRequest}, #5). Cố ý chỉ có ba trường của hợp đồng: không có
 * {@code branchId} (luôn là chi nhánh của người gọi), {@code source} (luôn {@code MANAGER}), {@code status} (luôn
 * {@code SCHEDULED}) nên client không đặt được chúng qua body (docs/adr/0138).
 */
public record CreateShiftAssignmentRequest(
        @Schema(description = "Nhân viên A06–A08 ACTIVE của chi nhánh người gọi", example = "12",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Vui lòng chọn nhân viên")
        Long staffId,

        @Schema(description = "Ca đang dùng của chi nhánh người gọi", example = "7",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Vui lòng chọn ca làm việc")
        Long shiftDefinitionId,

        @Schema(description = "Ngày làm, từ hôm nay trở đi (BR-NS-02), định dạng yyyy-MM-dd", example = "2026-11-24",
                type = "string", format = "date", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Vui lòng chọn ngày làm")
        LocalDate workDate) {
}
