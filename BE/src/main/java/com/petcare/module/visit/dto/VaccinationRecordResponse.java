package com.petcare.module.visit.dto;

import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Một mũi tiêm tại hệ thống (visit-v1 schema {@code VaccinationRecord}, BR-KB-04). Nằm trong {@code MedicalRecord}.
 * Mọi giá trị là cột đã chụp lúc tiêm; {@code lotNumber} / {@code expiryDate} chưa điền (nợ D046, docs/adr/0072).
 */
public record VaccinationRecordResponse(
        @Schema(description = "Mã mũi tiêm", example = "701")
        Long vaccinationId,

        @Schema(description = "Lượt tiêm", example = "9001")
        Long visitId,

        @Schema(description = "Chi nhánh tiêm", example = "3")
        Long branchId,

        @Schema(description = "Loại vaccine (BR-SP-07)", example = "5")
        Long vaccineTypeId,

        @Schema(description = "Tên loại vaccine", example = "Dại")
        String vaccineTypeName,

        @Schema(description = "Dòng phác đồ VET chọn (BR-KB-04)", example = "12")
        Long protocolId,

        @Schema(description = "Mũi thứ", example = "1")
        Integer doseNumber,

        @Schema(description = "Sản phẩm vaccine đã dùng", example = "81")
        Long productId,

        @Schema(description = "Tên sản phẩm", example = "Rabisin")
        String productName,

        @Schema(description = "Số lô đã trừ", example = "LOT-2026-09")
        String lotNumber,

        @Schema(description = "Hạn dùng của lô", example = "2027-06-30")
        LocalDate expiryDate,

        @Schema(description = "Ngày tiêm", example = "2026-10-22")
        LocalDate administeredOn,

        @Schema(description = "= ngày tiêm + khoảng cách của mũi trong phác đồ (BR-KB-04)", example = "2027-10-22")
        LocalDate nextDueDate,

        @Schema(description = "Đã có mũi mới hơn cùng loại vaccine (BR-TB-03)", example = "false")
        boolean superseded,

        @Schema(description = "VET ghi nhận", example = "45")
        Long recordedBy,

        @Schema(description = "Dòng Order vaccine", example = "9102")
        Long orderLineId) {
}
