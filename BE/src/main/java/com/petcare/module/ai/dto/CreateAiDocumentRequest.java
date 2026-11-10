package com.petcare.module.ai.dto;

import java.time.LocalDate;

import com.petcare.module.ai.entity.DocumentSpecies;
import com.petcare.module.ai.entity.DocumentTopic;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * ai-v1 {@code CreateAiDocumentRequest} (#6, Tài liệu#1). Tiêu đề và độ dài nội dung là {@code BR-AI-14} do service kiểm
 * (hợp đồng ghi 400 {@code BR-AI-14}), nên hai trường đó cố ý không có ràng buộc Bean Validation; nguồn và người rà
 * soát chỉ có {@code @Size} khớp độ dài cột (V34).
 */
public record CreateAiDocumentRequest(
        @Schema(description = "Tiêu đề, tối đa 200 ký tự (BR-AI-14)", example = "Lịch tiêm cho chó con",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String title,

        @NotNull(message = "Chủ đề không được để trống")
        @Schema(description = "Chủ đề", requiredMode = Schema.RequiredMode.REQUIRED)
        DocumentTopic topic,

        @NotNull(message = "Loài áp dụng không được để trống")
        @Schema(description = "Loài áp dụng; ALL = mọi loài", requiredMode = Schema.RequiredMode.REQUIRED)
        DocumentSpecies species,

        @NotNull(message = "Nội dung không được để trống")
        @Schema(description = "Markdown, tối đa 20 000 ký tự (BR-AI-14)",
                example = "## Mũi đầu tiên\nChó con thường tiêm mũi đầu từ 6 tuần tuổi.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String content,

        @Size(max = 500, message = "Ghi chú nguồn tối đa 500 ký tự")
        @Schema(description = "Lớp 1 — nguồn; bắt buộc khi xuất bản", example = "WSAVA Vaccination Guidelines 2024, mục 3",
                nullable = true)
        String sourceNote,

        @Size(max = 100, message = "Tên người rà soát tối đa 100 ký tự")
        @Schema(description = "Lớp 2 — bác sĩ thú y rà soát; bắt buộc khi xuất bản", example = "BS. Nguyễn Văn Nam",
                nullable = true)
        String reviewerName,

        @Schema(description = "Ngày rà soát; bắt buộc khi xuất bản", example = "2026-11-05", nullable = true)
        LocalDate reviewedOn) {
}
