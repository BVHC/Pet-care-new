package com.petcare.module.catalog.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** catalog-v1 {@code ProductCategory}. */
public record ProductCategoryResponse(
        Long categoryId,
        String name,
        @JsonProperty("isActive") boolean isActive) {
}
