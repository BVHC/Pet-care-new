package com.petcare.module.report.dto;

import java.time.LocalDate;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** report-v1 {@code LostRevenueReport}: Thất thu (BR-BC-02): chỉ Order hủy theo BR-BH-05 (UNPAID); hủy phiên trả thú không tính. */
public record LostRevenueReport(
        @Schema(description = "Ngày bắt đầu kỳ (giờ Việt Nam)", example = "2026-10-01")
        LocalDate from,
        @Schema(description = "Ngày kết thúc kỳ (giờ Việt Nam)", example = "2026-10-31")
        LocalDate to,
        @Schema(description = "Các Order thất thu, theo thời điểm hủy")
        List<LostRevenueRow> rows,
        @Schema(description = "Số Order thất thu", example = "3")
        int count,
        @Schema(description = "Tổng giá trị thất thu (VND)", example = "1250000")
        long totalAmount) {
}
