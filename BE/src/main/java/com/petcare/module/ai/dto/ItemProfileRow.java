package com.petcare.module.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** ai-v1 {@code ItemProfileRow} (UC94): một dịch vụ hoặc sản phẩm kèm hồ sơ gợi ý; {@code profile} vắng khi chưa có hồ sơ. */
public record ItemProfileRow(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ItemType itemType,

        @Schema(example = "101", requiredMode = Schema.RequiredMode.REQUIRED) Long itemId,

        @Schema(example = "Tắm & vệ sinh chó nhỏ", requiredMode = Schema.RequiredMode.REQUIRED) String name,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) RecoGroup group,

        @Schema(example = "true", requiredMode = Schema.RequiredMode.REQUIRED) boolean hasProfile,

        @Schema(nullable = true) ItemProfile profile) {
}
