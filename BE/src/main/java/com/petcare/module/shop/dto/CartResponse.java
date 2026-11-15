package com.petcare.module.shop.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** shop-v1 {@code Cart}: chỉ các dòng còn bán được; {@code subtotal} cộng đúng các dòng đó. */
public record CartResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<CartItemResponse> items,
        @Schema(description = "VND", example = "370000", minimum = "0",
                requiredMode = Schema.RequiredMode.REQUIRED) long subtotal) {
}
