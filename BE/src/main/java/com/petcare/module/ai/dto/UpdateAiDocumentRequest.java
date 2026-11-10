package com.petcare.module.ai.dto;

import java.time.LocalDate;

import com.petcare.module.ai.entity.DocumentSpecies;
import com.petcare.module.ai.entity.DocumentTopic;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * ai-v1 {@code UpdateAiDocumentRequest} (#8, Tài liệu#3). Trường vắng hoặc {@code null} = giữ nguyên. Độ dài tiêu đề và
 * nội dung là {@code BR-AI-14} do service kiểm. Sửa <b>nội dung</b> của tài liệu {@code PUBLISHED} phải kèm
 * {@code reviewerName} và {@code reviewedOn} mới (BR-AI-14, docs/adr/0130); đổi tiêu đề thì không cần nhưng vẫn tăng
 * {@code version}.
 */
public record UpdateAiDocumentRequest(
        @Schema(description = "Tiêu đề mới, tối đa 200 ký tự", example = "Lịch tiêm cho chó con (cập nhật)", nullable = true)
        String title,

        @Schema(description = "Chủ đề mới", nullable = true)
        DocumentTopic topic,

        @Schema(description = "Loài áp dụng mới", nullable = true)
        DocumentSpecies species,

        @Schema(description = "Nội dung Markdown mới, tối đa 20 000 ký tự", example = "## Mũi nhắc lại\nNhắc lại mỗi năm.",
                nullable = true)
        String content,

        @Size(max = 500, message = "Ghi chú nguồn tối đa 500 ký tự")
        @Schema(description = "Ghi chú nguồn mới", example = "WSAVA Vaccination Guidelines 2024, mục 4", nullable = true)
        String sourceNote,

        @Size(max = 100, message = "Tên người rà soát tối đa 100 ký tự")
        @Schema(description = "Người rà soát mới", example = "BS. Nguyễn Văn Nam", nullable = true)
        String reviewerName,

        @Schema(description = "Ngày rà soát mới", example = "2026-11-10", nullable = true)
        LocalDate reviewedOn) {
}
