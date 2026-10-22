package com.petcare.module.visit.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Body của {@code POST /api/visits/{visitId}/reassign} (visit-v1 {@code ReassignRequest}, UC45; docs/adr/0063). Lý do
 * bắt buộc (BR-TN-08): thiếu / chỉ khoảng trắng là 400 {@code VALIDATION_FAILED}. Compact constructor bỏ khoảng trắng
 * hai đầu (cả khoảng trắng Unicode như U+3000) <b>trước</b> Bean Validation (khuôn {@code LiftRestrictionRequest},
 * docs/adr/0055), nên chuỗi lưu đúng bằng chuỗi đã kiểm. {@code @Size} đếm theo UTF-16: chặt hơn cột
 * {@code visit_assignments.reason VARCHAR(300)}, không bao giờ lỏng hơn. Người mới có đủ điều kiện hay không
 * (BR-TN-05) kiểm ở service dưới khóa tài khoản.
 */
public record ReassignRequest(
        @Schema(description = "Người phụ trách mới: VET cho MEDICAL, CARETAKER cho GROOMING (BR-TN-05)", example = "46")
        @NotNull(message = "Thiếu nhân viên được gán")
        @Positive(message = "Mã nhân viên không hợp lệ")
        Long assigneeId,

        @Schema(description = "Lý do gán lại, bắt buộc (BR-TN-08)", example = "BS. Lan bị khóa tài khoản giữa ca khám")
        @NotBlank(message = "Bắt buộc nhập lý do gán lại")
        @Size(max = 300, message = "Lý do tối đa 300 ký tự")
        String reason) {

    public ReassignRequest {
        reason = reason == null ? null : reason.strip();
    }
}
