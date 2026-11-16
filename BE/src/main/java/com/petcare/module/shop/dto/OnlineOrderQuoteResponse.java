package com.petcare.module.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** shop-v1 {@code OnlineOrderQuote}: báo giá không giữ hàng, không ghi gì (ST09). */
public record OnlineOrderQuoteResponse(
        @Schema(example = "3", requiredMode = Schema.RequiredMode.REQUIRED) Long branchId,
        @Schema(example = "7", requiredMode = Schema.RequiredMode.REQUIRED) Long addressId,
        @Schema(description = "VND", example = "370000", minimum = "0",
                requiredMode = Schema.RequiredMode.REQUIRED) long subtotal,
        @Schema(description = "BR-SP-08", example = "30000", minimum = "0",
                requiredMode = Schema.RequiredMode.REQUIRED) long shippingFee,
        @Schema(description = "Miễn phí vì tiền hàng đạt ngưỡng", example = "false",
                requiredMode = Schema.RequiredMode.REQUIRED) boolean freeShippingApplied,
        @Schema(description = "VND", example = "400000", minimum = "0",
                requiredMode = Schema.RequiredMode.REQUIRED) long total) {
}
