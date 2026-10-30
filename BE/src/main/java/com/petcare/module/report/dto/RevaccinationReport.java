package com.petcare.module.report.dto;

import java.time.LocalDate;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** report-v1 {@code RevaccinationReport}: Tỷ lệ quay lại tái chủng (BR-BC-04): đo khách quay lại tiêm đúng hạn tại hệ thống. */
public record RevaccinationReport(
        @Schema(description = "Ngày bắt đầu kỳ (giờ Việt Nam)", example = "2026-10-01")
        LocalDate from,
        @Schema(description = "Ngày kết thúc kỳ (giờ Việt Nam)", example = "2026-10-31")
        LocalDate to,
        @Schema(description = "Theo chi nhánh và loại vaccine")
        List<ReturnRateRow> rows) {
}
