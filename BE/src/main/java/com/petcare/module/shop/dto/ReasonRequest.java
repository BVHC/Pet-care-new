package com.petcare.module.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** shop-v1 {@code ReasonRequest}: lý do khách hủy đơn (cột {@code online_orders.cancel_reason VARCHAR(300)}). */
public record ReasonRequest(
        @Schema(description = "Lý do, bắt buộc, tối đa 300 ký tự", example = "Khách đổi ý", maxLength = 300,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "Lý do là bắt buộc")
        @Size(max = 300, message = "Lý do tối đa 300 ký tự") String reason) {
}
