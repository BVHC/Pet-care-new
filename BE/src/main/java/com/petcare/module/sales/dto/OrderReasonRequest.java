package com.petcare.module.sales.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * Lý do hủy Order từ {@code PENDING} (sales-v1 {@code ReasonRequest}; cột {@code orders.cancel_reason VARCHAR(300)}).
 * Trống / chỉ khoảng trắng là vi phạm rule của từng thao tác (BR-BH-05) nên service kiểm, không phải Bean Validation.
 */
public record OrderReasonRequest(
        @Schema(description = "Lý do hủy, bắt buộc, tối đa 300 ký tự", example = "Khách bỏ về không thanh toán",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @Size(max = 300, message = "Lý do tối đa 300 ký tự")
        String reason) {
}
