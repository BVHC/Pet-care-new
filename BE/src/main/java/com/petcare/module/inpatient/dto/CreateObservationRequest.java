package com.petcare.module.inpatient.dto;

import java.math.BigDecimal;

import com.petcare.module.inpatient.entity.Appetite;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Ghi chỉ số theo dõi (inpatient-v1 {@code CreateObservationRequest}, #9; BR-LT-16). Người ghi, thời điểm và ca do server
 * đặt: trường lạ trong body bị bỏ qua. Ở đây chỉ chặn hình thức (null của cờ, độ dài ghi chú); "không có chỉ số hay ghi
 * chú nào" và giới hạn giá trị là {@code BR-LT-16} do service kiểm sau 409 ({@code AdmissionObservation.invalidReason}).
 * {@code abnormal} là {@code Boolean} + {@code @NotNull} để thiếu trường là 400, không âm thầm thành {@code false}. Không
 * dùng {@code @NotBlank} cho ghi chú (để lọt khoảng trắng Unicode, D072-h).
 */
public record CreateObservationRequest(
        @Schema(description = "Nhiệt độ (°C), làm tròn một chữ số lẻ", example = "38.6", nullable = true)
        BigDecimal temperatureC,

        @Schema(description = "Nhịp tim (lần/phút), từ 1 đến 32767", example = "120", nullable = true)
        Integer heartRate,

        @Schema(description = "Nhịp thở (lần/phút), từ 1 đến 32767", example = "30", nullable = true)
        Integer respiratoryRate,

        @Schema(description = "Cân nặng (kg), làm tròn hai chữ số lẻ; có thì ghi thêm vào hồ sơ cân của thú (BR-KH-04)",
                example = "5.55", nullable = true)
        BigDecimal weightKg,

        @Schema(description = "Mức ăn uống", example = "REDUCED", nullable = true)
        Appetite appetite,

        @Schema(description = "Ghi chú, tối đa 500 ký tự", example = "Nằm yên, bỏ ăn buổi sáng", maxLength = 500,
                nullable = true)
        @Size(max = 500, message = "Ghi chú tối đa 500 ký tự")
        String note,

        @Schema(description = "true báo VET phụ trách và khách (BR-LT-16)", example = "false",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Vui lòng cho biết mục theo dõi có bất thường không")
        Boolean abnormal) {
}
