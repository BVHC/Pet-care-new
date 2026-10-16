package com.petcare.module.inventory.dto;

import java.time.LocalDate;

import com.petcare.module.catalog.api.ProductType;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Tồn của một sản phẩm tại chi nhánh (inventory-v1 {@code InventoryItem}, BR-KO-01, 07). Tồn khả dụng chỉ tính lô chưa
 * hết hạn; {@code expiredQuantity} là số còn trong các lô đã hết hạn (cần lập điều chỉnh lý do hết hạn).
 */
public record InventoryItemResponse(
        @Schema(description = "Mã sản phẩm", example = "12")
        Long productId,

        @Schema(description = "Tên sản phẩm", example = "Vaccine 7 bệnh chó")
        String productName,

        @Schema(description = "Mã SKU", example = "VAC-7B")
        String sku,

        @Schema(description = "Đơn vị tính", example = "liều")
        String unit,

        @Schema(description = "Loại sản phẩm", example = "VACCINE")
        ProductType productType,

        @Schema(description = "Có quản lý hạn dùng theo lô (BR-SP-05)", example = "true")
        boolean tracksExpiry,

        @Schema(description = "Tổng số trong mọi lô, kể cả lô đã hết hạn", example = "60")
        int totalQuantity,

        @Schema(description = "Tồn khả dụng: tổng các lô chưa hết hạn (BR-KO-01)", example = "50")
        int availableQuantity,

        @Schema(description = "Tồn tối thiểu (BR-KO-07); 0 nếu chưa đặt", example = "20")
        int minQuantity,

        @Schema(description = "Tồn khả dụng dưới tồn tối thiểu", example = "false")
        boolean belowMin,

        @Schema(description = "Hạn dùng gần nhất của lô chưa hết hạn còn tồn", example = "2027-03-01")
        LocalDate nearestExpiryDate,

        @Schema(description = "Số còn trong các lô đã hết hạn", example = "10")
        int expiredQuantity) {
}
