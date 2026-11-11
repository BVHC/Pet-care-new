package com.petcare.module.ai.dto;

import java.time.Instant;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** ai-v1 {@code RecommendationResponse} (UC92): {@code generatedAt} là lúc tính, kể cả khi lấy từ bộ nhớ đệm (BR-AI-23). */
public record RecommendationResponse(
        @Schema(example = "12", requiredMode = Schema.RequiredMode.REQUIRED) Long petId,

        @Schema(example = "2026-11-11T02:00:00Z", requiredMode = Schema.RequiredMode.REQUIRED) Instant generatedAt,

        @Schema(description = "Sắp theo nhóm rồi điểm giảm dần", requiredMode = Schema.RequiredMode.REQUIRED)
        List<RecommendedItem> items) {
}
