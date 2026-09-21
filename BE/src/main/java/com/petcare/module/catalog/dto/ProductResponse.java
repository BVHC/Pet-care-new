package com.petcare.module.catalog.dto;

import com.petcare.platform.enums.ProductCategory;
import com.petcare.platform.enums.ProductUnit;

import java.util.UUID;

public record ProductResponse(
        UUID productId,
        UUID organizationId,
        String sku,
        String barcode,
        String name,
        ProductCategory category,
        ProductUnit unit,
        String basePrice,
        String costPrice,
        boolean isActive
) {
}
