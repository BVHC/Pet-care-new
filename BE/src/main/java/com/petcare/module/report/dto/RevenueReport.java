package com.petcare.module.report.dto;

import java.time.LocalDate;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** report-v1 {@code RevenueReport}: Doanh thu (BR-BC-02): Order PAID có thời điểm thanh toán trong kỳ, theo đơn giá snapshot. */
public record RevenueReport(
        @Schema(description = "Ngày bắt đầu kỳ (giờ Việt Nam)", example = "2026-10-01")
        LocalDate from,
        @Schema(description = "Ngày kết thúc kỳ (giờ Việt Nam)", example = "2026-10-31")
        LocalDate to,
        @Schema(description = "Doanh thu theo chi nhánh và nguồn")
        List<RevenueRow> rows,
        @Schema(description = "Tổng doanh thu (VND)", example = "3450000")
        long totalAmount) {
}
