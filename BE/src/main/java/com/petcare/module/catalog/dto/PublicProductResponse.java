package com.petcare.module.catalog.dto;

import java.util.List;

/**
 * catalog-v1 {@code PublicProduct} ({@code GET /api/public/products[/{productId}]}, UC16; BR-CK-01, 03). Không có số
 * lượng tồn, SKU, loại sản phẩm hay cờ nội bộ: {@code inStockBranchIds} chỉ liệt kê chi nhánh ACTIVE còn tồn khả dụng.
 * {@code purchasableOnline} là cờ suy ra theo BR-BH-07 (giỏ online chỉ nhận hàng GOODS), không phải {@code productType}.
 */
public record PublicProductResponse(
        Long productId,
        String name,
        Long categoryId,
        String categoryName,
        long price,
        String unit,
        String description,
        String imageUrl,
        List<String> galleryUrls,
        boolean purchasableOnline,
        List<Long> inStockBranchIds) {
}
