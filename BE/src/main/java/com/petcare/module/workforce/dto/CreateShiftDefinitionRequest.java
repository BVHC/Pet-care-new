package com.petcare.module.workforce.dto;

import java.time.LocalTime;

import com.fasterxml.jackson.annotation.JsonFormat;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Tạo ca làm việc (workforce-v1 {@code CreateShiftDefinitionRequest}, #2). Cố ý không có {@code branchId}: chi nhánh
 * luôn là chi nhánh của người gọi, nên client không đặt được ca vào chi nhánh khác (docs/adr/0138).
 */
public record CreateShiftDefinitionRequest(
        @Schema(description = "Tên ca, duy nhất trong chi nhánh, không phân biệt hoa thường (BR-NS-01)",
                example = "Ca sáng", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "Vui lòng nhập tên ca")
        @Size(max = 60, message = "Tên ca tối đa 60 ký tự")
        String name,

        @Schema(description = "Giờ bắt đầu, định dạng HH:mm", example = "08:00", type = "string",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Vui lòng nhập giờ bắt đầu")
        @JsonFormat(pattern = "HH:mm")
        LocalTime startTime,

        @Schema(description = "Giờ kết thúc, định dạng HH:mm; phải sau giờ bắt đầu, không có ca qua nửa đêm",
                example = "12:00", type = "string", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Vui lòng nhập giờ kết thúc")
        @JsonFormat(pattern = "HH:mm")
        LocalTime endTime) {
}
