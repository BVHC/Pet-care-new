package com.petcare.module.inpatient.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Hủy y lệnh (inpatient-v1 {@code AdmissionOrderReasonRequest}, #8; BR-LT-15). Tên riêng vì springdoc gộp schema theo tên
 * đơn giản mà {@code identity.dto.ReasonRequest}, {@code shop.dto.ReasonRequest} đã có (khuôn {@code ShiftReasonRequest}).
 * Lý do rỗng sau {@code strip()} là {@code BR-LT-15} do service kiểm sau 409 (không dùng {@code @NotBlank}, D072-h).
 */
public record AdmissionOrderReasonRequest(
        @Schema(description = "Lý do hủy, tối đa 300 ký tự", example = "Thú đã được ăn trước giờ", maxLength = 300,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Vui lòng nhập lý do hủy")
        @Size(max = 300, message = "Lý do hủy tối đa 300 ký tự")
        String reason) {
}
