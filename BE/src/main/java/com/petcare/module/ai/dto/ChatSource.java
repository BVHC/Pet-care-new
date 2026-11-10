package com.petcare.module.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** ai-v1 {@code ChatSource}: tài liệu nguồn của câu trả lời, lấy từ siêu dữ liệu truy hồi (BR-AI-05). */
public record ChatSource(
        @Schema(example = "4", requiredMode = Schema.RequiredMode.REQUIRED) Long documentId,
        @Schema(example = "Lịch tiêm cho chó con", requiredMode = Schema.RequiredMode.REQUIRED) String title) {
}
