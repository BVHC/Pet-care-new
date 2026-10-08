package com.petcare.module.catalog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body của {@code POST /api/product-categories} (catalog-v1 {@code ProductCategoryRequest}, UC28). */
public record ProductCategoryRequest(
        @NotBlank(message = "Tên danh mục không được để trống")
        @Size(max = 100, message = "Tên danh mục tối đa 100 ký tự")
        String name) {
}
