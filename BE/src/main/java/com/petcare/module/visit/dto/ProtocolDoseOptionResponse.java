package com.petcare.module.visit.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Một mũi đang dùng của phác đồ (visit-v1 schema {@code ProtocolDoseOption}, BR-SP-02); giá trị hiện tại của phác đồ. */
public record ProtocolDoseOptionResponse(
        @Schema(description = "Dòng phác đồ", example = "12")
        Long protocolId,

        @Schema(description = "Mũi thứ", example = "1")
        Integer doseNumber,

        @Schema(description = "Khoảng cách tới mũi kế tiếp (ngày)", example = "21")
        Integer intervalDays,

        @Schema(description = "Tuổi tối thiểu (tuần)", example = "8")
        Integer minAgeWeeks,

        @Schema(description = "Bắt buộc khi lưu trú (BR-LT-05)", example = "true")
        boolean requiredForBoarding) {
}
