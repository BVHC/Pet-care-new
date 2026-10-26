package com.petcare.module.visit.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** Kết quả ghi mũi tiêm (visit-v1 {@code RecordVaccinationResult}, UC49; docs/adr/0074). */
public record RecordVaccinationResultResponse(
        @Schema(description = "Mũi tiêm vừa ghi")
        VaccinationRecordResponse vaccination,

        @Schema(description = "Cảnh báo không chặn, ví dụ UNDER_MIN_AGE (BR-KB-04); rỗng nếu không có", example = "[\"UNDER_MIN_AGE\"]")
        List<String> warnings) {
}
