package com.petcare.module.workforce.dto;

import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * Một mục (ngày, ca) của đăng ký tuần (workforce-v1 {@code ShiftRegistrationItem}, #15). Chỉ kiểm hình thức (không null);
 * ngày trong tuần, ca thuộc chi nhánh / đang dùng, trùng cặp và chồng giờ là BR-NS-11 do service kiểm cả lô
 * (docs/adr/0149, mục "Đăng ký tuần — ghi #15").
 */
public record ShiftRegistrationItem(
        @Schema(description = "Phải nằm trong tuần của đường dẫn", example = "2026-11-24", type = "string",
                format = "date", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Vui lòng chọn ngày làm")
        LocalDate workDate,

        @Schema(description = "Ca đang dùng của chi nhánh", example = "12", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Vui lòng chọn ca làm việc")
        Long shiftDefinitionId) {
}
