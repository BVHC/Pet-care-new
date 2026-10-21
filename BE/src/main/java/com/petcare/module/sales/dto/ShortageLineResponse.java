package com.petcare.module.sales.dto;

import com.petcare.module.sales.entity.OrderLineType;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Một dòng hàng / thuốc thiếu tồn khi thu tiền (sales-v1 {@code ShortageLine}, #13). Mỗi dòng của sản phẩm thiếu có
 * một mục: {@code requested} là số lượng của dòng, {@code available} là tồn khả dụng của sản phẩm tại chi nhánh.
 */
public record ShortageLineResponse(
        @Schema(description = "Order chứa dòng", example = "101")
        Long orderId,

        @Schema(description = "Dòng thiếu", example = "1001")
        Long lineId,

        @Schema(description = "Loại dòng", example = "GOODS")
        OrderLineType lineType,

        @Schema(description = "Sản phẩm", example = "20")
        Long productId,

        @Schema(description = "Tên sản phẩm lúc thêm dòng", example = "Hạt cho chó 2kg")
        String productName,

        @Schema(description = "Số lượng của dòng", example = "3")
        int requested,

        @Schema(description = "Tồn khả dụng của sản phẩm tại chi nhánh", example = "2")
        int available) {
}
