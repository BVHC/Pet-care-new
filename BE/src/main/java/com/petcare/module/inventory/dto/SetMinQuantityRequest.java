package com.petcare.module.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Body của {@code PUT /api/inventory/{productId}/min-quantity} (inventory-v1 {@code SetMinQuantityRequest}, BR-KO-07). */
public record SetMinQuantityRequest(
        @Schema(description = "Tồn tối thiểu, 0…1.000.000; 0 = không cảnh báo", example = "20")
        @NotNull(message = "Vui lòng nhập tồn tối thiểu")
        @Min(value = 0, message = "Tồn tối thiểu không được âm")
        @Max(value = 1_000_000, message = "Tồn tối thiểu tối đa 1.000.000")
        Integer minQuantity) {
}
