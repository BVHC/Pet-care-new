package com.petcare.module.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** shop-v1 {@code OnlineOrderLine}: giá là bản chụp lúc đặt (BR-BH-03). */
public record OnlineOrderLineResponse(
        @Schema(example = "12", requiredMode = Schema.RequiredMode.REQUIRED) Long productId,
        @Schema(example = "Hạt cho chó 2kg", requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(example = "túi", requiredMode = Schema.RequiredMode.REQUIRED) String unit,
        @Schema(description = "Snapshot (BR-BH-03)", example = "185000", minimum = "0",
                requiredMode = Schema.RequiredMode.REQUIRED) long unitPrice,
        @Schema(example = "2", requiredMode = Schema.RequiredMode.REQUIRED) int quantity,
        @Schema(description = "VND", example = "370000", minimum = "0",
                requiredMode = Schema.RequiredMode.REQUIRED) long lineTotal) {
}
