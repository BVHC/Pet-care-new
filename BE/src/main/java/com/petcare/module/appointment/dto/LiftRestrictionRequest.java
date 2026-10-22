package com.petcare.module.appointment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body của {@code POST /api/customers/{customerId}/booking-restriction/lift} (appointment-v1
 * {@code LiftRestrictionRequest}, BR-LH-09; docs/adr/0055). Lý do bắt buộc theo hợp đồng ({@code reason: string:300}); cột
 * {@code lift_reason VARCHAR(300)} (erd §5). Compact constructor bỏ khoảng trắng hai đầu (cả khoảng trắng Unicode như
 * U+3000) <b>trước</b> Bean Validation, nên {@code @NotBlank} — vốn chỉ {@code trim()} ký tự ≤ U+0020 — kiểm giá trị đã
 * chuẩn hóa và chuỗi lưu đúng bằng chuỗi đã kiểm. {@code @Size} đếm theo UTF-16: chặt hơn cột, không bao giờ lỏng hơn.
 */
public record LiftRestrictionRequest(
        @Schema(description = "Lý do gỡ sớm (bắt buộc, ghi audit)", example = "Khách xác nhận lý do vắng hợp lệ")
        @NotBlank(message = "Vui lòng nhập lý do")
        @Size(max = 300, message = "Lý do tối đa 300 ký tự")
        String reason) {

    public LiftRestrictionRequest {
        reason = reason == null ? null : reason.strip();
    }
}
