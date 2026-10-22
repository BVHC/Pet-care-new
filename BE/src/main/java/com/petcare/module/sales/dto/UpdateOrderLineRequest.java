package com.petcare.module.sales.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Sửa dòng (sales-v1 {@code UpdateOrderLineRequest}): giảm số lượng và / hoặc đổi dịch vụ của dòng tự sinh (A5).
 * Gửi cả hai trường rỗng là 400 {@code VALIDATION_FAILED} (service kiểm).
 */
public record UpdateOrderLineRequest(
        @Schema(description = "Số lượng mới, chỉ được giảm", example = "1")
        @Min(value = 1, message = "Số lượng phải lớn hơn 0")
        @Max(value = 1_000_000, message = "Số lượng tối đa 1.000.000")
        Integer quantity,

        @Schema(description = "Dịch vụ mới của dòng tự sinh; giá và tên được chụp lại tại thời điểm đổi", example = "5")
        Long serviceId) {
}
