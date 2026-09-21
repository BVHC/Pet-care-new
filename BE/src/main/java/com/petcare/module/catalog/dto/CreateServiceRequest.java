package com.petcare.module.catalog.dto;

import com.petcare.platform.enums.ServiceCategory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * docs/api/catalog-v1.md C1 — {@code POST /services} (RULE-05-03). {@code organizationId} lấy
 * từ {@code actor.getOrganizationId()}, cùng lý do {@link CreateProductRequest}.
 * {@code requiredResources} khai báo inline (ASSUMPTION A1) — service_required_resources được
 * tạo cùng transaction với Service.
 */
public record CreateServiceRequest(
        @NotBlank @Size(max = 50) String code,
        @NotBlank @Size(max = 255) String name,
        @NotNull ServiceCategory category,
        @NotBlank @Pattern(regexp = "^-?\\d+\\.\\d{2}$", message = "basePrice phải là chuỗi thập phân 2 chữ số, vd 199000.00") String basePrice,
        @Min(1) Integer durationMinutes,
        Boolean isActive,
        @Valid List<RequiredResourceItem> requiredResources
) {
}
