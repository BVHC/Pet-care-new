package com.petcare.module.visit.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Body của {@code POST /api/visits/{visitId}/move} (visit-v1 {@code MoveVisitRequest}, UC45, A3; docs/adr/0061): đặt lượt
 * ngay trước lượt {@code placeBeforeVisitId}. Lượt đích cùng chi nhánh, còn {@code WAITING}, cùng phía ranh giới cấp
 * cứu — kiểm ở service.
 */
public record MoveVisitRequest(
        @Schema(description = "Visit WAITING cùng chi nhánh (A3)", example = "9002")
        @NotNull(message = "Thiếu lượt đích")
        @Positive(message = "Mã lượt đích không hợp lệ")
        Long placeBeforeVisitId) {
}
