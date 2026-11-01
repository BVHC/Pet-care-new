package com.petcare.module.inventory.dto;

import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;

/** Một lô còn tồn (inventory-v1 {@code StockLot}); sắp theo hạn dùng tăng dần — thứ tự FEFO (BR-KO-05). */
public record StockLotResponse(
        @Schema(description = "Mã lô", example = "77")
        Long stockLotId,

        @Schema(description = "Số lô; null với lô mặc định", example = "LOT-2610-A")
        String lotNumber,

        @Schema(description = "Hạn dùng; null với lô mặc định", example = "2027-03-01")
        LocalDate expiryDate,

        @Schema(description = "Số dư của lô", example = "30")
        int quantity,

        @Schema(description = "Lô mặc định của sản phẩm không quản lý hạn dùng", example = "false")
        @JsonProperty("isDefault") boolean isDefault,

        @Schema(description = "Đã hết hạn (hạn dùng trước hôm nay): không xuất được (BR-KO-05)", example = "false")
        boolean expired,

        @Schema(description = "Lô này có hạn dùng trong [CFG] stock.expiry_warning_days ngày tới, gồm cả lô đã hết hạn "
                + "(BR-KO-07); cùng nghĩa với cờ expiringSoon của dòng tồn. Lô mặc định (không hạn) luôn false",
                example = "true")
        boolean expiringSoon) {
}
