package com.petcare.module.catalog.dto;

import com.petcare.platform.enums.ProductCategory;
import com.petcare.platform.enums.ProductUnit;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * docs/api/openapi/catalog-v1.yaml #UpdateProductRequest — {@code PATCH /products/{id}}
 * (RULE-05-02). {@code sku} bất biến (PROPOSED, TBD Q3/Q8 docs/api/catalog-v1.md §E) — không có
 * field ở đây để rebind. Field null = giữ nguyên (partial update); vô hiệu hóa sản phẩm dùng
 * {@code isActive=false} thay vì xóa (RULE-05-02 cấm hard-delete khi đã phát sinh giao dịch).
 */
public record UpdateProductRequest(
        @Size(max = 255) @Pattern(regexp = ".*\\S.*", message = "name không được để trống") String name,
        @Size(max = 50) String barcode,
        ProductCategory category,
        ProductUnit unit,
        @Pattern(regexp = "^-?\\d+\\.\\d{2}$", message = "basePrice phải là chuỗi thập phân 2 chữ số, vd 199000.00") String basePrice,
        @Pattern(regexp = "^-?\\d+\\.\\d{2}$", message = "costPrice phải là chuỗi thập phân 2 chữ số, vd 199000.00") String costPrice,
        Boolean isActive
) {
}
