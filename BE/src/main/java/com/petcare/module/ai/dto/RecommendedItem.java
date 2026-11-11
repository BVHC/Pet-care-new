package com.petcare.module.ai.dto;

import java.math.BigDecimal;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** ai-v1 {@code RecommendedItem}: một mục được gợi ý, điểm 0–100 làm tròn một chữ số thập phân (BR-AI-21, 22). */
public record RecommendedItem(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) RecoGroup group,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ItemType itemType,

        @Schema(example = "101", requiredMode = Schema.RequiredMode.REQUIRED) Long itemId,

        @Schema(example = "Tắm & vệ sinh chó nhỏ", requiredMode = Schema.RequiredMode.REQUIRED) String name,

        @Schema(description = "VND", example = "120000", minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
        long price,

        @Schema(description = "BR-CK-02", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean priceIsFrom,

        @Schema(description = "`đêm` với BOARDING", example = "đêm", nullable = true) String priceUnit,

        @Schema(example = "https://cdn.example.com/products/hat-cho-cho.png", maxLength = 500, nullable = true)
        String imageUrl,

        @Schema(description = "0–100 (BR-AI-21)", example = "92.0", requiredMode = Schema.RequiredMode.REQUIRED)
        BigDecimal score,

        @Schema(description = "Sinh từ mẫu, không dùng LLM (BR-AI-22)", requiredMode = Schema.RequiredMode.REQUIRED)
        List<String> reasons) {
}
