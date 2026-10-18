package com.petcare.module.appointment.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.petcare.module.appointment.entity.AppointmentCancelSource;
import com.petcare.module.appointment.entity.AppointmentChannel;
import com.petcare.module.appointment.entity.AppointmentStatus;
import com.petcare.module.catalog.api.ServiceGroup;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Một lịch hẹn (appointment-v1 schema {@code Appointment}, UC39, UC40). Chỉ dựng bởi {@code AppointmentMapper}. Không
 * lộ {@code booked_by}, {@code reminded_at}, {@code created_at}, {@code updated_at}. {@code visitId} null tới khi visit có
 * query tra Visit theo lịch hẹn (nợ D028); {@code canReschedule}, {@code canCancel} theo vai trò người xem (docs/adr/0043).
 */
public record AppointmentResponse(
        @Schema(description = "Mã lịch hẹn", example = "501")
        Long appointmentId,

        @Schema(description = "Giữ nguyên khi đổi lịch (BR-LH-06)", example = "LH-261015-0007")
        String code,

        @Schema(description = "Hồ sơ khách: chủ hiện tại của thú lúc đặt", example = "88")
        Long customerId,

        @Schema(description = "Họ tên khách", example = "Nguyễn Văn An")
        String customerName,

        @Schema(description = "Thú cưng", example = "12")
        Long petId,

        @Schema(description = "Tên thú cưng", example = "Milu")
        String petName,

        @Schema(description = "Chi nhánh", example = "3")
        Long branchId,

        @Schema(description = "Tên chi nhánh", example = "Pet Care Quận 1")
        String branchName,

        @Schema(description = "Dịch vụ", example = "7")
        Long serviceId,

        @Schema(description = "Tên dịch vụ", example = "Khám tổng quát")
        String serviceName,

        @Schema(description = "Nhóm dịch vụ lúc đặt", example = "MEDICAL")
        ServiceGroup serviceGroup,

        @Schema(description = "Ngày hẹn, theo giờ Việt Nam", example = "2026-10-20")
        LocalDate slotDate,

        @Schema(description = "Giờ bắt đầu khung", type = "string", pattern = "^[0-2][0-9]:[0-5][0-9]$",
                example = "08:30")
        @JsonFormat(pattern = "HH:mm")
        LocalTime slotStart,

        @Schema(description = "Trạng thái (03 #3)", example = "BOOKED")
        AppointmentStatus status,

        @Schema(description = "ONLINE: khách tự đặt · COUNTER: lễ tân đặt hộ", example = "ONLINE")
        AppointmentChannel channel,

        @Schema(description = "Tối đa 3 [CFG]", example = "0")
        int rescheduleCount,

        @Schema(description = "Hủy khi còn dưới 12 giờ [CFG] (BR-LH-07)", example = "false")
        boolean lateCancel,

        @Schema(description = "Nguồn hủy; null nếu chưa hủy", example = "CUSTOMER")
        AppointmentCancelSource cancelSource,

        @Schema(description = "Lý do hủy; null nếu không có", example = "Bận việc đột xuất")
        String cancelReason,

        @Schema(description = "Thời điểm hủy; null nếu chưa hủy", example = "2026-10-18T03:00:00Z")
        Instant cancelledAt,

        @Schema(description = "Ghi chú của khách; null nếu không có", example = "Bé hay sợ tiếng ồn")
        String note,

        @Schema(description = "Visit tạo khi check-in; null nếu chưa check-in", example = "901")
        Long visitId,

        @Schema(description = "Theo vai trò người xem: còn lượt, còn đủ 12 giờ với khách (BR-LH-06)",
                example = "true")
        boolean canReschedule,

        @Schema(description = "Lịch còn BOOKED và chưa tới giờ hẹn (BR-LH-07)", example = "true")
        boolean canCancel) {
}
