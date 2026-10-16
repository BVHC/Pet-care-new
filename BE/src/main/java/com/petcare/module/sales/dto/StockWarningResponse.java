package com.petcare.module.sales.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Cảnh báo thiếu tồn khi thêm hàng bán lẻ (sales-v1 {@code StockWarning}, BR-BH-04): chỉ cảnh báo, không chặn. */
public record StockWarningResponse(
        @Schema(description = "Sản phẩm", example = "12")
        Long productId,

        @Schema(description = "Tổng số lượng sản phẩm này trong Order sau khi thêm", example = "5")
        int requested,

        @Schema(description = "Tồn khả dụng của chi nhánh (BR-KO-01)", example = "3")
        int available) {
}
