package com.petcare.module.catalog.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Body của {@code PATCH /api/products/{id}}; trường {@code null} là không đổi. SKU và loại sản phẩm không đổi
 * được (catalog-v1 A1).
 */
public record UpdateProductRequest(
        Long categoryId,

        @Pattern(regexp = ".*\\S.*", message = "Tên sản phẩm không được để trống")
        @Size(max = 200, message = "Tên sản phẩm tối đa 200 ký tự")
        String name,

        Boolean isPrescription,

        Boolean tracksExpiry,

        Long vaccineTypeId,

        @Pattern(regexp = ".*\\S.*", message = "Đơn vị tính không được để trống")
        @Size(max = 20, message = "Đơn vị tính tối đa 20 ký tự")
        String unit,

        @PositiveOrZero(message = "Giá không được âm")
        Long price,

        String description,

        @Size(max = 500, message = "Đường dẫn ảnh tối đa 500 ký tự")
        String imageUrl,

        Boolean isActive) {
}
