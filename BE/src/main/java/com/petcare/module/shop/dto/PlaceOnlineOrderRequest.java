package com.petcare.module.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** shop-v1 {@code PlaceOnlineOrderRequest}. */
public record PlaceOnlineOrderRequest(
        @Schema(example = "3", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Chi nhánh xử lý là bắt buộc") Long branchId,
        @Schema(example = "7", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Địa chỉ giao hàng là bắt buộc") Long addressId) {
}
