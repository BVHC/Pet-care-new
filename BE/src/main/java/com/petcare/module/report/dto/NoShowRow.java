package com.petcare.module.report.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;

/** report-v1 {@code NoShowRow}: Tỷ lệ NO_SHOW và hủy muộn của một chi nhánh (BR-BC-03 (3)). */
public record NoShowRow(
        @Schema(description = "Chi nhánh", example = "2")
        Long branchId,
        @Schema(description = "Lịch hẹn có khung giờ trong kỳ (không tính lịch phòng khám hủy)", example = "40")
        int dueCount,
        @Schema(description = "Số lịch NO_SHOW", example = "3")
        int noShowCount,
        @Schema(description = "Số lịch hủy muộn", example = "2")
        int lateCancelCount,
        @Schema(description = "Tỷ lệ NO_SHOW trên lịch đến hạn, 0–1 (4 chữ số thập phân)", example = "0.0750")
        BigDecimal noShowRate,
        @Schema(description = "Tỷ lệ hủy muộn trên lịch đến hạn, 0–1 (4 chữ số thập phân)", example = "0.0500")
        BigDecimal lateCancelRate) {
}
