package com.petcare.module.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** ai-v1 {@code PetChoice}: thú để khách chọn khi {@code NEED_PET_CHOICE} (BR-AI-27); chỉ có ở {@code /me/ai/chat}. */
public record PetChoice(
        @Schema(example = "12", requiredMode = Schema.RequiredMode.REQUIRED) Long petId,
        @Schema(example = "Mochi", requiredMode = Schema.RequiredMode.REQUIRED) String name) {
}
