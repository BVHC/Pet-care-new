package com.petcare.module.report.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;

/** report-v1 {@code FeedbackStatRow}: Số feedback và mức hài lòng trung bình của một chi nhánh (BR-BC-03 (6)). */
public record FeedbackStatRow(
        @Schema(description = "Chi nhánh; null = feedback không gắn chi nhánh (chỉ SUPER_MANAGER thấy)", example = "2", nullable = true)
        Long branchId,
        @Schema(description = "Tên chi nhánh; null khi không gắn chi nhánh", example = "PetCare Quận 1", nullable = true)
        String branchName,
        @Schema(description = "Số feedback trong kỳ", example = "14")
        int count,
        @Schema(description = "Điểm hài lòng trung bình 1–5 (2 chữ số thập phân); null khi chưa có điểm", example = "4.35", nullable = true)
        BigDecimal averageRating) {
}
