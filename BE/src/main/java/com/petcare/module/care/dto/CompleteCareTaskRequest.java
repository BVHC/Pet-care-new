package com.petcare.module.care.dto;

import com.petcare.module.care.entity.CareTaskResult;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * care-v1 {@code CompleteCareTaskRequest} (#3, Care Task#2). Thiếu {@code result}, hoặc {@code note} trống / toàn khoảng
 * trắng là {@code BR-TB-05} do service kiểm; cố ý <b>không</b> dùng {@code @NotNull} / {@code @NotBlank} vì chúng trả
 * {@code VALIDATION_FAILED} không kèm mã rule (docs/adr/0080).
 */
public record CompleteCareTaskRequest(
        @Schema(description = "Kết quả cuộc gọi: LIÊN_HỆ_ĐƯỢC / KHÔNG_LIÊN_LẠC_ĐƯỢC (BR-TB-05)", example = "REACHED",
                requiredMode = Schema.RequiredMode.REQUIRED)
        CareTaskResult result,

        @Schema(description = "Ghi chú cuộc gọi, bắt buộc (BR-TB-05)", example = "Khách hẹn tiêm tuần sau.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @Size(max = 2000, message = "Ghi chú tối đa 2000 ký tự")
        String note) {
}
