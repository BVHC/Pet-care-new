package com.petcare.module.visit.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * Body của {@code POST /api/visits/{visitId}/weight} (visit-v1 {@code RecordWeightRequest}, UC48; docs/adr/0066).
 * Chỉ kiểm có giá trị; {@code ≤ 0} và vượt {@code NUMERIC(6,2)} là 400 {@code BR-KH-04} do customer
 * ({@code PetApi.recordWeight}) trả. Thú, nguồn và người ghi do server lấy từ lượt và phiên, không nhận từ body.
 */
public record RecordWeightRequest(
        @Schema(description = "Cân nặng (kg), lớn hơn 0 (BR-KH-04)", example = "12.35")
        @NotNull(message = "Bắt buộc nhập cân nặng")
        BigDecimal weightKg) {
}
