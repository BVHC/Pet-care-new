package com.petcare.module.report.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** report-v1 {@code LowStockRow}: Sản phẩm có tồn khả dụng dưới tồn tối thiểu (BR-KO-07). */
public record LowStockRow(
        @Schema(description = "Chi nhánh", example = "2")
        Long branchId,
        @Schema(description = "Mã sản phẩm", example = "15")
        Long productId,
        @Schema(description = "Tên sản phẩm", example = "Thức ăn hạt cho chó 5kg")
        String productName,
        @Schema(description = "Tồn khả dụng (lô chưa hết hạn)", example = "4")
        int availableQuantity,
        @Schema(description = "Tồn tối thiểu đã đặt", example = "10")
        int minQuantity) {
}
