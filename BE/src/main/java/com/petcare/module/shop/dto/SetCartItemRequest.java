package com.petcare.module.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** shop-v1 {@code SetCartItemRequest}. Ngoài 1–99 là {@code VALIDATION_FAILED} (Bean Validation), sai kiểu là {@code MALFORMED_REQUEST}. */
public record SetCartItemRequest(
        @NotNull(message = "Số lượng là bắt buộc") @Min(value = 1, message = "Số lượng phải từ 1 đến 99")
        @Max(value = 99, message = "Số lượng phải từ 1 đến 99") @Schema(description = "1–99 (BR-BH-07)", example = "2",
                minimum = "1", maximum = "99", requiredMode = Schema.RequiredMode.REQUIRED) Integer quantity) {
}
