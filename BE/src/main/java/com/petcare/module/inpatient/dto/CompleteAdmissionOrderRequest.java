package com.petcare.module.inpatient.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Thực hiện y lệnh (inpatient-v1 {@code CompleteAdmissionOrderRequest}, #7; BR-LT-15). Ghi chú rỗng sau {@code strip()}
 * là {@code BR-LT-15} do service kiểm sau 409 (không dùng {@code @NotBlank}, D072-h).
 */
public record CompleteAdmissionOrderRequest(
        @Schema(description = "Ghi chú thực hiện, tối đa 300 ký tự", example = "Đã tiêm, thú không phản ứng",
                maxLength = 300, requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Vui lòng nhập ghi chú thực hiện")
        @Size(max = 300, message = "Ghi chú thực hiện tối đa 300 ký tự")
        String note) {
}
