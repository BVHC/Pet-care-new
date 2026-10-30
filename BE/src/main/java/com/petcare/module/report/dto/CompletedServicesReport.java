package com.petcare.module.report.dto;

import java.time.LocalDate;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** report-v1 {@code CompletedServicesReport}: Lượt dịch vụ hoàn tất theo nhóm và theo dịch vụ (BR-BC-03 (2)). */
public record CompletedServicesReport(
        @Schema(description = "Ngày bắt đầu kỳ (giờ Việt Nam)", example = "2026-10-01")
        LocalDate from,
        @Schema(description = "Ngày kết thúc kỳ (giờ Việt Nam)", example = "2026-10-31")
        LocalDate to,
        @Schema(description = "Số lượt theo chi nhánh và dịch vụ")
        List<ServiceCountRow> rows) {
}
