package com.petcare.module.catalog.dto;

import com.petcare.platform.enums.ServiceCategory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * docs/api/openapi/catalog-v1.yaml #UpdateServiceRequest — {@code PATCH /services/{id}}.
 * {@code code} bất biến (cùng lý do {@link UpdateProductRequest#barcode()} — Q3/Q8).
 * {@code requiredResources}: null = giữ nguyên; non-null (kể cả rỗng) = replace-as-whole
 * (ASSUMPTION A1).
 */
public record UpdateServiceRequest(
        @Size(max = 255) @Pattern(regexp = ".*\\S.*", message = "name không được để trống") String name,
        ServiceCategory category,
        @Pattern(regexp = "^\\d+\\.\\d{2}$", message = "basePrice phải là chuỗi thập phân 2 chữ số, không âm, vd 199000.00") String basePrice,
        @Min(1) Integer durationMinutes,
        Boolean isActive,
        @Valid List<RequiredResourceItem> requiredResources
) {
}
