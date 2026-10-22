package com.petcare.module.visit.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.petcare.module.visit.entity.ExternalPurchaseReason;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Một dòng đơn thuốc (visit-v1 schema {@code PrescriptionItem}, BR-KB-03). Có từ T27 phần 0 vì nằm trong
 * {@code MedicalRecord}; danh sách luôn rỗng cho tới khi T38 ghi đơn thuốc (docs/adr/0066).
 */
public record PrescriptionItemResponse(
        @Schema(description = "Mã dòng đơn thuốc", example = "501")
        Long itemId,

        @Schema(description = "Thuốc kê đơn (BR-SP-01)", example = "77")
        Long productId,

        @Schema(description = "Tên thuốc", example = "Amoxicillin 250mg")
        String productName,

        @Schema(description = "Số lượng", example = "10")
        Integer quantity,

        @Schema(description = "Liều dùng, in trên đơn", example = "Ngày 2 lần, mỗi lần 1 viên sau ăn")
        String dosageInstructions,

        @Schema(description = "Mua ngoài: vẫn in trên đơn, không sinh dòng Order", example = "false")
        @JsonProperty("isExternalPurchase") boolean isExternalPurchase,

        @Schema(description = "Lý do mua ngoài (BR-KB-03, BR-BH-04)", example = "OUT_OF_STOCK_AT_PRESCRIBE")
        ExternalPurchaseReason externalReason,

        @Schema(description = "Dòng Order sinh ra; null khi mua ngoài", example = "9101")
        Long orderLineId) {
}
