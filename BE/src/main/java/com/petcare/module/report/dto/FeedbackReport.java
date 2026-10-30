package com.petcare.module.report.dto;

import java.time.LocalDate;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** report-v1 {@code FeedbackReport}: Feedback theo chi nhánh: số lượng và mức hài lòng trung bình (BR-BC-03 (6)). */
public record FeedbackReport(
        @Schema(description = "Ngày bắt đầu kỳ (giờ Việt Nam)", example = "2026-10-01")
        LocalDate from,
        @Schema(description = "Ngày kết thúc kỳ (giờ Việt Nam)", example = "2026-10-31")
        LocalDate to,
        @Schema(description = "Theo chi nhánh")
        List<FeedbackStatRow> rows) {
}
