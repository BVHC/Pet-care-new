package com.petcare.module.inventory.dto;

import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;

/** Một dòng phiếu nhập (inventory-v1 {@code ReceiptLine}). {@code stockLotId} chỉ có sau khi xác nhận. */
public record ReceiptLineResponse(
        @Schema(description = "Mã dòng", example = "101")
        Long lineId,

        @Schema(description = "Mã sản phẩm", example = "12")
        Long productId,

        @Schema(description = "Tên sản phẩm", example = "Vaccine 7 bệnh chó")
        String productName,

        @Schema(description = "Số lượng", example = "50")
        int quantity,

        @Schema(description = "Giá nhập mỗi đơn vị (VND)", example = "85000")
        long unitCost,

        @Schema(description = "Số lô", example = "LOT-2610-A")
        String lotNumber,

        @Schema(description = "Hạn dùng", example = "2027-10-01")
        LocalDate expiryDate,

        @Schema(description = "Lô đã cộng tồn (sau khi xác nhận)", example = "77")
        Long stockLotId) {
}
