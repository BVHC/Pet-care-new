package com.petcare.module.appointment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * Body không bắt buộc của {@code POST /api/appointments/{id}/cancel} (appointment-v1 {@code CancelAppointmentRequest},
 * UC40; docs/adr/0049). Lý do không bắt buộc (A4); service bỏ khoảng trắng hai đầu, rỗng thì không lưu. Nguồn hủy suy ra
 * từ vai trò người hủy, không nhận từ client.
 */
public record CancelAppointmentRequest(
        @Schema(description = "Lý do hủy", example = "Bận việc đột xuất")
        @Size(max = 300, message = "Lý do tối đa 300 ký tự")
        String reason) {
}
