package com.petcare.module.visit.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Body của {@code POST /api/visits/{visitId}/vaccinations} (visit-v1 {@code RecordVaccinationRequest}, UC49; docs/adr/0074).
 * Chỉ kiểm hình thức; loại / mũi / sản phẩm có tồn tại, khớp loài và còn dùng hay không kiểm ở service dưới khóa. Ngày
 * tiêm, chi nhánh, thú và lô do server quyết định; trường lạ bị bỏ qua.
 */
public record RecordVaccinationRequest(
        @Schema(description = "Loại vaccine đúng loài của thú (BR-SP-07)", example = "5")
        @NotNull(message = "Thiếu loại vaccine")
        @Positive(message = "Mã loại vaccine không hợp lệ")
        Long vaccineTypeId,

        @Schema(description = "Mũi VET chọn, có thể khác gợi ý (BR-KB-04)", example = "12")
        @NotNull(message = "Thiếu mũi tiêm")
        @Positive(message = "Mã mũi tiêm không hợp lệ")
        Long protocolId,

        @Schema(description = "Vaccine thuộc loại đã chọn, còn tồn khả dụng", example = "81")
        @NotNull(message = "Thiếu sản phẩm vaccine")
        @Positive(message = "Mã sản phẩm không hợp lệ")
        Long productId) {
}
