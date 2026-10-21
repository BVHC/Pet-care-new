package com.petcare.module.visit.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * Body không bắt buộc của {@code POST /api/visits/{visitId}/cancel} (visit-v1 {@code CancelVisitRequest}, UC45, A4;
 * docs/adr/0062). Lý do không bắt buộc; service bỏ khoảng trắng hai đầu, rỗng thì không lưu (khuôn
 * {@code CancelAppointmentRequest}, docs/adr/0049). {@code @Size} đếm theo UTF-16: chặt hơn cột
 * {@code cancel_reason VARCHAR(300)}, không bao giờ lỏng hơn.
 */
public record CancelVisitRequest(
        @Schema(description = "Lý do hủy (không bắt buộc, A4)", example = "Khách bận việc, bỏ về")
        @Size(max = 300, message = "Lý do tối đa 300 ký tự")
        String reason) {
}
