package com.petcare.module.report.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;

/** report-v1 {@code ReturnRateRow}: Tỷ lệ quay lại tái chủng theo chi nhánh và loại vaccine (BR-BC-04). */
public record ReturnRateRow(
        @Schema(description = "Chi nhánh của mũi trước", example = "2")
        Long branchId,
        @Schema(description = "Mã loại vaccine", example = "3")
        Long vaccineTypeId,
        @Schema(description = "Tên loại vaccine", example = "Dại")
        String vaccineTypeName,
        @Schema(description = "Số mũi có ngày tái chủng trong kỳ (trừ thú mất trước ngày đó)", example = "20")
        int dueCount,
        @Schema(description = "Số mũi đã được tiêm lại tại hệ thống không muộn quá hạn cho phép", example = "15")
        int returnedCount,
        @Schema(description = "Tỷ lệ quay lại, 0–1 (4 chữ số thập phân)", example = "0.7500")
        BigDecimal rate) {
}
