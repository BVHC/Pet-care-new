package com.petcare.module.sales.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** Tạo Order bán lẻ (sales-v1 {@code CreateRetailOrderRequest}, UC66): luôn gắn hồ sơ khách (hợp đồng A1). */
public record CreateRetailOrderRequest(
        @Schema(description = "Hồ sơ khách mua hàng; khách lạ thì tạo hồ sơ tại quầy trước (A1)", example = "5",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Vui lòng chọn khách hàng")
        Long customerId) {
}
