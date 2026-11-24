package com.petcare.module.inpatient.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.petcare.module.inpatient.entity.Appetite;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Mục theo dõi nội trú (inpatient-v1 {@code Observation}, #9–#10). Đúng 11 trường theo thứ tự hợp đồng; trường nullable
 * luôn có mặt trong JSON. {@code recordedByName} là tên hiện tại của người ghi (docs/adr/0149 mục 31).
 */
public record ObservationResponse(
        @Schema(description = "Mã mục theo dõi", example = "301")
        Long observationId,

        @Schema(description = "Ca nội trú", example = "51")
        Long admissionId,

        @Schema(description = "Tên người ghi (tên hiện tại)", example = "Trần Văn Minh")
        String recordedByName,

        @Schema(description = "Nhiệt độ (°C)", example = "38.6", nullable = true)
        BigDecimal temperatureC,

        @Schema(description = "Nhịp tim (lần/phút)", example = "120", nullable = true)
        Integer heartRate,

        @Schema(description = "Nhịp thở (lần/phút)", example = "30", nullable = true)
        Integer respiratoryRate,

        @Schema(description = "Cân nặng (kg)", example = "5.55", nullable = true)
        BigDecimal weightKg,

        @Schema(description = "Mức ăn uống", example = "REDUCED", nullable = true)
        Appetite appetite,

        @Schema(description = "Ghi chú (đã bỏ khoảng trắng đầu / cuối)", example = "Nằm yên, bỏ ăn buổi sáng",
                nullable = true)
        String note,

        @Schema(description = "Mục được đánh dấu bất thường", example = "false")
        boolean abnormal,

        @Schema(description = "Thời điểm ghi", example = "2026-11-25T01:05:00Z")
        Instant createdAt) {
}
