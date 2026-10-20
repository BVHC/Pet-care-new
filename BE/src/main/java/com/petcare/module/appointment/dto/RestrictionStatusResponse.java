package com.petcare.module.appointment.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Tình trạng hạn chế đặt online (appointment-v1 schema {@code RestrictionStatus}, #7–9). {@code restricted} cùng điều kiện
 * với guard đặt online ({@code starts_at ≤ now < ends_at AND lifted_at IS NULL}, erd §5); không bị hạn chế thì
 * {@code restriction = null} (00-method: trường không bắt buộc có thể {@code null}; docs/adr/0055). Chỉ dựng bởi
 * {@code BookingRestrictionMapper}.
 */
public record RestrictionStatusResponse(
        @Schema(description = "Đang bị hạn chế đặt online (BR-LH-09)", example = "true")
        boolean restricted,

        @Schema(description = "Lần hạn chế đang hiệu lực; null nếu không bị hạn chế")
        BookingRestrictionResponse restriction) {
}
