package com.petcare.module.catalog.dto;

import com.petcare.module.catalog.api.ProductType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Body của {@code POST /api/products} (catalog-v1 {@code CreateProductRequest}, UC29). Chỉ kiểm hình thức;
 * BR-SP-01, 05, 07 kiểm ở {@code ProductService} (convention 06).
 */
public record CreateProductRequest(
        @NotNull(message = "Thiếu danh mục sản phẩm")
        Long categoryId,

        @NotBlank(message = "SKU không được để trống")
        @Size(max = 50, message = "SKU tối đa 50 ký tự")
        String sku,

        @NotBlank(message = "Tên sản phẩm không được để trống")
        @Size(max = 200, message = "Tên sản phẩm tối đa 200 ký tự")
        String name,

        @NotNull(message = "Thiếu loại sản phẩm")
        ProductType productType,

        @NotNull(message = "Thiếu cờ thuốc kê đơn")
        Boolean isPrescription,

        @NotNull(message = "Thiếu cờ quản lý hạn dùng")
        Boolean tracksExpiry,

        Long vaccineTypeId,

        @NotBlank(message = "Đơn vị tính không được để trống")
        @Size(max = 20, message = "Đơn vị tính tối đa 20 ký tự")
        String unit,

        @NotNull(message = "Thiếu giá")
        @PositiveOrZero(message = "Giá không được âm")
        Long price,

        String description,

        @Size(max = 500, message = "Đường dẫn ảnh tối đa 500 ký tự")
        String imageUrl) {
}
