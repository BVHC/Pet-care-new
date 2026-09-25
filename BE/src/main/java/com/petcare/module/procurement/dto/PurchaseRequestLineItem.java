package com.petcare.module.procurement.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * docs/02-business-rules.md RULE-13-01 — "...số lượng đề xuất, đơn giá dự kiến và nhà cung cấp
 * khuyến nghị". {@code recommendedSupplierName} là gợi ý tự do (không FK) — khác hẳn Supplier
 * thật gắn ở CreatePurchaseOrder (RULE-13-04).
 */
public record PurchaseRequestLineItem(
        @NotNull UUID productId,
        @Positive int requestedQuantity,
        @NotBlank @Pattern(regexp = "^\\d+\\.\\d{2}$", message = "estimatedUnitPrice phải là chuỗi thập phân 2 chữ số, không âm, vd 199000.00") String estimatedUnitPrice,
        @Size(max = 255) String recommendedSupplierName
) {
}
