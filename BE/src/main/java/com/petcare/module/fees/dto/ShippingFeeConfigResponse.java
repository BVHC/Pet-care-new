package com.petcare.module.fees.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

/** fees-v1 {@code ShippingFeeConfig}. */
public record ShippingFeeConfigResponse(
        @Schema(description = "Phí cơ bản (BR-SP-08)", example = "30000", minimum = "0") long baseFee,

        @Schema(description = "0 = không miễn phí", example = "500000", minimum = "0") long freeThreshold,

        @Schema(description = "Lần cập nhật gần nhất", example = "2026-11-15T03:00:00Z") Instant updatedAt) {
}
