package com.petcare.module.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Một dòng phiếu điều chỉnh (inventory-v1 {@code AdjustmentLine}). */
public record AdjustmentLineResponse(
        @Schema(description = "Mã lô", example = "77")
        Long stockLotId,

        @Schema(description = "Mã sản phẩm", example = "12")
        Long productId,

        @Schema(description = "Tên sản phẩm", example = "Vaccine 7 bệnh chó")
        String productName,

        @Schema(description = "Số lô; null với lô mặc định", example = "LOT-2610-A")
        String lotNumber,

        @Schema(description = "Số lượng thay đổi", example = "-3")
        int quantityDelta,

        @Schema(description = "Số dư của lô sau điều chỉnh", example = "27")
        int balanceAfter) {
}
