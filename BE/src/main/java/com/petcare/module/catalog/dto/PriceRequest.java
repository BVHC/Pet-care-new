package com.petcare.module.catalog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * docs/api/openapi/catalog-v1.yaml #PriceRequest — {@code PUT .../price} (RULE-05-05).
 * Không enforce "không vượt khung giá Org" — khung chính sách chưa số hóa ở ERD (TBD Q7,
 * docs/api/catalog-v1.md §E); chỉ validate format + không âm.
 */
public record PriceRequest(
        @NotBlank @Pattern(regexp = "^\\d+\\.\\d{2}$", message = "price phải là chuỗi thập phân 2 chữ số, không âm, vd 199000.00") String price
) {
}
