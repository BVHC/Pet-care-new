package com.petcare.module.appointment.dto;

import java.time.LocalDate;
import java.time.LocalTime;

import com.fasterxml.jackson.annotation.JsonFormat;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Body của {@code POST /api/appointments} (appointment-v1 {@code BookAppointmentRequest}, UC39; docs/adr/0043). Không có
 * {@code customerId} / {@code channel}: khách của lịch là chủ hiện tại của thú, kênh suy ra từ vai trò người đặt (A2);
 * trường lạ bị bỏ qua. Id phải dương ({@code AppointmentLocks.lockPet} từ chối id ≤ 0). Khung giờ, cửa sổ đặt và mọi rule
 * kiểm ở service dưới khóa.
 */
public record BookAppointmentRequest(
        @Schema(description = "Thú cưng được đặt lịch", example = "12")
        @NotNull(message = "Thiếu thú cưng")
        @Positive(message = "Mã thú cưng không hợp lệ")
        Long petId,

        @Schema(description = "Chi nhánh", example = "3")
        @NotNull(message = "Thiếu chi nhánh")
        @Positive(message = "Mã chi nhánh không hợp lệ")
        Long branchId,

        @Schema(description = "Nhóm MEDICAL hoặc GROOMING (BR-LH-01)", example = "7")
        @NotNull(message = "Thiếu dịch vụ")
        @Positive(message = "Mã dịch vụ không hợp lệ")
        Long serviceId,

        @Schema(description = "Ngày hẹn, theo giờ Việt Nam", example = "2026-10-20")
        @NotNull(message = "Thiếu ngày hẹn")
        LocalDate slotDate,

        @Schema(description = "Giờ bắt đầu khung 30 phút", type = "string", pattern = "^[0-2][0-9]:[0-5][0-9]$",
                example = "08:30")
        @NotNull(message = "Thiếu giờ bắt đầu của khung")
        @JsonFormat(pattern = "HH:mm")
        LocalTime slotStart,

        @Schema(description = "Ghi chú của khách", example = "Bé hay sợ tiếng ồn")
        @Size(max = 500, message = "Ghi chú tối đa 500 ký tự")
        String note) {
}
