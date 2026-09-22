package com.petcare.module.catalog.dto;

import com.petcare.platform.enums.ProductCategory;
import com.petcare.platform.enums.ProductUnit;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * docs/api/catalog-v1.md C1 — {@code POST /products} (RULE-05-01, RULE-05-02 UNIQUE
 * (organizationId, sku)). {@code organizationId} không nằm trong request — lấy trực tiếp từ
 * {@code actor.getOrganizationId()} (RULE-05-01: chỉ Organization Admin của chính Org đó được
 * gọi, không có path/body param org id). Money là chuỗi thập phân 2 chữ số (docs/api/openapi/
 * catalog-v1.yaml #Money) để tránh sai số IEEE-754, parse BigDecimal ở Mapper.
 */
public record CreateProductRequest(
        @NotBlank @Size(max = 50) String sku,
        @Size(max = 50) String barcode,
        @NotBlank @Size(max = 255) String name,
        @NotNull ProductCategory category,
        @NotNull ProductUnit unit,
        @NotBlank @Pattern(regexp = "^\\d+\\.\\d{2}$", message = "basePrice phải là chuỗi thập phân 2 chữ số, không âm, vd 199000.00") String basePrice,
        @Pattern(regexp = "^\\d+\\.\\d{2}$", message = "costPrice phải là chuỗi thập phân 2 chữ số, không âm, vd 199000.00") String costPrice,
        Boolean isActive
) {
}
