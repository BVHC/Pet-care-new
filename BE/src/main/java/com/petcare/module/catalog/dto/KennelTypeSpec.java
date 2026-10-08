package com.petcare.module.catalog.dto;

import java.math.BigDecimal;

import com.petcare.module.customer.api.Species;

/**
 * catalog-v1 {@code KennelTypeSpec}: phần riêng của dịch vụ nhóm {@code BOARDING} (BR-SP-04). Không gắn Bean
 * Validation vì thiếu loài / cân nặng phải trả {@code BR-SP-04}, do {@code ServiceCatalogService} kiểm.
 */
public record KennelTypeSpec(Species species, BigDecimal maxWeightKg) {
}
