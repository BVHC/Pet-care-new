package com.petcare.module.sales.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Kết quả thêm dòng (sales-v1 {@code AddOrderLineResult}): {@code stockWarning} chỉ có khi hàng bán lẻ thiếu tồn. */
public record AddOrderLineResult(
        @Schema(description = "Order sau khi thêm dòng")
        OrderResponse order,

        @Schema(description = "Mã dòng vừa thêm", example = "31")
        Long lineId,

        @Schema(description = "Có khi hàng bán lẻ thiếu tồn khả dụng — cảnh báo, không chặn (BR-BH-04)")
        StockWarningResponse stockWarning) {
}
