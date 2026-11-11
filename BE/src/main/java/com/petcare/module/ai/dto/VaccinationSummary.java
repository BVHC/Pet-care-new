package com.petcare.module.ai.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** ai-v1 {@code VaccinationSummary} (#3): lịch tiêm của một thú, tất định, chỉ mũi ghi nhận tại hệ thống (BR-AI-11). */
public record VaccinationSummary(
        @Schema(example = "12", requiredMode = Schema.RequiredMode.REQUIRED) Long petId,

        @Schema(example = "Mochi", requiredMode = Schema.RequiredMode.REQUIRED) String petName,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<VaccinationItem> items,

        @Schema(description = "Câu hiển thị trong khung chat, sinh từ mẫu cố định, không qua LLM (BR-AI-11)",
                example = "Lịch tiêm của Mochi (chỉ tính các mũi ghi nhận tại hệ thống):\n- Dại: còn hạn, đã tiêm mũi 1 ngày "
                        + "16/10/2026; mũi tiếp theo hạn 16/10/2027.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String message,

        @Schema(example = "Thông tin tham khảo, không thay thế khám thú y.", requiredMode = Schema.RequiredMode.REQUIRED)
        String disclaimer) {
}
