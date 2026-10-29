package com.petcare.module.boarding.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Nhận thú (boarding-v1 {@code CheckInRequest}, #14). {@code kennelId} và {@code weightKg} không {@code @NotNull}: hợp
 * đồng trả 400 {@code BR-LT-08} (không phải {@code VALIDATION_FAILED}) khi thiếu chuồng hoặc cân nặng.
 */
public record CheckInRequest(
        @Schema(description = "Chuồng AVAILABLE, đúng loại đã đặt (BR-LT-08)", example = "12")
        Long kennelId,

        @Schema(description = "Cân nặng lúc nhận, kg, > 0 (BR-LT-08, BR-KH-04)", example = "4.30")
        BigDecimal weightKg,

        @Schema(description = "Tình trạng sức khỏe quan sát được", example = "Tỉnh táo, lông sạch",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "Vui lòng nhập tình trạng sức khỏe lúc nhận")
        @Size(max = 1000, message = "Tình trạng sức khỏe tối đa 1000 ký tự")
        String healthCondition,

        @Schema(description = "Đồ gửi kèm", example = "Chăn, 1 túi hạt")
        @Size(max = 1000, message = "Đồ gửi kèm tối đa 1000 ký tự")
        String belongings,

        @Schema(description = "Chế độ ăn riêng", example = "Ăn hạt ướt 2 bữa/ngày")
        @Size(max = 1000, message = "Chế độ ăn riêng tối đa 1000 ký tự")
        String dietInstructions,

        @Schema(description = "SĐT liên hệ khẩn, dùng cho Care Task quá hạn đón (BR-TB-05)", example = "0901234567",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "Vui lòng nhập số điện thoại liên hệ khẩn")
        @Pattern(regexp = "^0[0-9]{9,10}$", message = "Số điện thoại liên hệ khẩn không hợp lệ")
        String emergencyPhone) {
}
