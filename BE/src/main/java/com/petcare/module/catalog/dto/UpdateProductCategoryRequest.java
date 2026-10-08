package com.petcare.module.catalog.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Body của {@code PATCH /api/product-categories/{id}}; trường {@code null} là không đổi. */
public record UpdateProductCategoryRequest(
        @Pattern(regexp = ".*\\S.*", message = "Tên danh mục không được để trống")
        @Size(max = 100, message = "Tên danh mục tối đa 100 ký tự")
        String name,

        Boolean isActive) {
}
