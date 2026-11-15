package com.petcare.module.fees.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * fees-v1 {@code SaveShippingFeeConfigRequest}. Số âm là BR-SP-08 do service kiểm (400 nêu giá trị sai), thiếu trường là
 * {@code VALIDATION_FAILED}.
 */
public record SaveShippingFeeConfigRequest(
        @NotNull(message = "Phí cơ bản là bắt buộc") @Schema(description = "VND", example = "30000",
                requiredMode = Schema.RequiredMode.REQUIRED) Long baseFee,

        @NotNull(message = "Ngưỡng miễn phí là bắt buộc") @Schema(description = "VND", example = "500000",
                requiredMode = Schema.RequiredMode.REQUIRED) Long freeThreshold) {
}
