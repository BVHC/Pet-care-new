package com.petcare.module.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** shop-v1 {@code CartItem}: giá là giá hiện tại của danh mục, chỉ để xem (BR-BH-07). */
public record CartItemResponse(
        @Schema(example = "12", requiredMode = Schema.RequiredMode.REQUIRED) Long productId,
        @Schema(example = "Hạt cho chó 2kg", requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(example = "túi", requiredMode = Schema.RequiredMode.REQUIRED) String unit,
        @Schema(description = "Giá hiện tại, chỉ để xem (BR-BH-07)", example = "185000", minimum = "0",
                requiredMode = Schema.RequiredMode.REQUIRED) long unitPrice,
        @Schema(example = "2", requiredMode = Schema.RequiredMode.REQUIRED) int quantity,
        @Schema(example = "https://cdn.petcare.vn/products/12.jpg", maxLength = 500, nullable = true) String imageUrl,
        @Schema(description = "false nếu hết hàng toàn chuỗi", example = "true",
                requiredMode = Schema.RequiredMode.REQUIRED) boolean available) {
}
