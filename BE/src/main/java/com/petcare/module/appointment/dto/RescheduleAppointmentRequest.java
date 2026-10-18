package com.petcare.module.appointment.dto;

import java.time.LocalDate;
import java.time.LocalTime;

import com.fasterxml.jackson.annotation.JsonFormat;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * Body của {@code POST /api/appointments/{id}/reschedule} (appointment-v1 {@code RescheduleRequest}, UC40; docs/adr/0045).
 * Chỉ có khung mới: chi nhánh, thú, dịch vụ giữ nguyên (BR-LH-06), trường lạ bị bỏ qua. Khung hợp lệ (BR-LH-02), cửa sổ
 * đặt (BR-LH-04) và mọi rule kiểm ở service dưới khóa.
 */
public record RescheduleAppointmentRequest(
        @Schema(description = "Ngày hẹn mới, theo giờ Việt Nam", example = "2026-10-21")
        @NotNull(message = "Thiếu ngày hẹn")
        LocalDate slotDate,

        @Schema(description = "Giờ bắt đầu khung 30 phút mới", type = "string", pattern = "^[0-2][0-9]:[0-5][0-9]$",
                example = "09:00")
        @NotNull(message = "Thiếu giờ bắt đầu của khung")
        @JsonFormat(pattern = "HH:mm")
        LocalTime slotStart) {
}
