package com.petcare.module.catalog.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.petcare.module.catalog.api.ProductType;

/** catalog-v1 {@code Product}. */
public record ProductResponse(
        Long productId,
        Long categoryId,
        String sku,
        String name,
        ProductType productType,
        @JsonProperty("isPrescription") boolean isPrescription,
        boolean tracksExpiry,
        Long vaccineTypeId,
        String unit,
        long price,
        String description,
        String imageUrl,
        List<String> galleryUrls,
        @JsonProperty("isActive") boolean isActive) {
}
