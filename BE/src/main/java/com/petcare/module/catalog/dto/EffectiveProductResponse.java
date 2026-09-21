package com.petcare.module.catalog.dto;

import java.util.UUID;

/**
 * docs/api/openapi/catalog-v1.yaml #EffectiveProductPage.items — {@code GET
 * /stores/{id}/products} (RULE-05-06). {@code price}: override nếu có, else
 * {@code products.base_price} (RULE-05-05/07). {@code isActive}: luôn phản ánh
 * {@code products.is_active} gốc — store_products KHÔNG có cột khả dụng riêng (bất đối xứng ERD,
 * chỉ Service mới có override khả dụng theo Store).
 */
public record EffectiveProductResponse(
        UUID productId,
        String name,
        String price,
        boolean isActive
) {
}
