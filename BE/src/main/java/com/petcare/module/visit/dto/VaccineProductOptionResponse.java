package com.petcare.module.visit.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Sản phẩm vaccine đang kinh doanh còn tồn khả dụng tại chi nhánh của lượt (visit-v1 {@code VaccineProductOption}, BR-KO-01). */
public record VaccineProductOptionResponse(
        @Schema(description = "Sản phẩm vaccine", example = "81")
        Long productId,

        @Schema(description = "Tên sản phẩm", example = "Rabisin")
        String name,

        @Schema(description = "Tồn khả dụng (lô chưa hết hạn) tại chi nhánh", example = "24")
        Integer availableQuantity) {
}
