package com.petcare.module.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** ai-v1 {@code EmergencyBranch}: chi nhánh nhận cấp cứu ngoài giờ (BR-AI-03, BR-CN-05). */
public record EmergencyBranch(
        @Schema(example = "2", requiredMode = Schema.RequiredMode.REQUIRED) Long branchId,
        @Schema(example = "Pet Care Cầu Giấy", requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(example = "…", requiredMode = Schema.RequiredMode.REQUIRED) String address,
        @Schema(example = "0901234567", requiredMode = Schema.RequiredMode.REQUIRED) String phone) {
}
