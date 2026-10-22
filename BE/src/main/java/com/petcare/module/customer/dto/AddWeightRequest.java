package com.petcare.module.customer.dto;

import java.math.BigDecimal;
import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

/**
 * Body của {@code POST /api/pets/{petId}/weights} (customer-v1 {@code AddWeightRequest}, UC24; BR-KH-04). Không dùng
 * {@code @Positive}: cân ≤ 0 phải trả 400 {@code BR-KH-04} (contract), nên service kiểm. {@code @Digits(4, 2)} khớp
 * {@code NUMERIC(6,2)} để số quá lớn bị từ chối thay vì lỗi ghi DB. {@code measuredAt} null = bây giờ; không ở tương lai.
 */
public record AddWeightRequest(
        @Schema(description = "Cân nặng (kg), tối đa 2 chữ số thập phân", example = "8.5")
        @NotNull(message = "Vui lòng nhập cân nặng")
        @Digits(integer = 4, fraction = 2, message = "Cân nặng tối đa 4 chữ số nguyên và 2 chữ số thập phân")
        BigDecimal weightKg,

        @Schema(description = "Thời điểm cân; null = bây giờ, không ở tương lai", example = "2026-10-16T03:00:00Z")
        Instant measuredAt) {
}
