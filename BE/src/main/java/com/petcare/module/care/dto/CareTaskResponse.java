package com.petcare.module.care.dto;

import java.time.Instant;
import java.time.LocalDate;

import com.petcare.module.care.api.CareTaskApi.CareTaskType;
import com.petcare.module.care.entity.CareTaskResult;
import com.petcare.module.care.entity.CareTaskStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Một việc gọi điện của lễ tân (care-v1 schema {@code CareTask}, UC87). Chỉ dựng bởi {@code CareTaskMapper}. Đúng một
 * trong ba id nguồn khác null theo {@code type}; {@code result} / {@code note} chỉ có khi {@code DONE}, {@code cancelReason}
 * chỉ có khi {@code CANCELLED}.
 */
public record CareTaskResponse(
        @Schema(description = "Mã việc", example = "311")
        Long careTaskId,

        @Schema(description = "Loại: nhắc tái chủng, quá hạn tái chủng, nhắc tái khám, quá hạn đón", example = "VACCINE_DUE")
        CareTaskType type,

        @Schema(description = "Trạng thái", example = "OPEN")
        CareTaskStatus status,

        @Schema(description = "Chi nhánh phụ trách gọi điện", example = "2")
        Long branchId,

        @Schema(description = "Khách cần gọi (chủ hiện tại với việc còn OPEN; việc đã đóng giữ chủ lúc đóng)", example = "58")
        Long customerId,

        @Schema(description = "Họ tên khách", example = "Nguyễn Văn An")
        String customerName,

        @Schema(description = "Số cần gọi: PICKUP_OVERDUE là SĐT khẩn ghi lúc nhận thú, loại khác là SĐT hồ sơ khách; "
                + "null nếu chưa có", example = "0901234567")
        String phoneToCall,

        @Schema(description = "Thú cưng", example = "104")
        Long petId,

        @Schema(description = "Tên thú cưng", example = "Mực")
        String petName,

        @Schema(description = "Mũi tiêm nguồn (VACCINE_DUE, VACCINE_OVERDUE); null với loại khác", example = "77")
        Long vaccinationId,

        @Schema(description = "Mã Visit của bệnh án có hẹn tái khám (FOLLOW_UP_DUE); null với loại khác", example = "901")
        Long medicalRecordVisitId,

        @Schema(description = "Đặt chỗ lưu trú quá hạn đón (PICKUP_OVERDUE); null với loại khác", example = "12")
        Long boardingBookingId,

        @Schema(description = "Ngày tái chủng / tái khám / ngày quá hạn", example = "2026-11-03")
        LocalDate dueDate,

        @Schema(description = "Mô tả ngắn: loại vaccine, ngày tái khám hoặc đặt chỗ", example = "Tái chủng Dại")
        String context,

        @Schema(description = "Kết quả cuộc gọi; chỉ có khi DONE", example = "REACHED")
        CareTaskResult result,

        @Schema(description = "Ghi chú cuộc gọi; chỉ có khi DONE", example = "Khách hẹn tiêm tuần sau.")
        String note,

        @Schema(description = "Lý do hủy; chỉ có khi CANCELLED. Do hệ thống hủy thì là câu có mã rule",
                example = "Đã tiêm lại (BR-TB-03)")
        String cancelReason,

        @Schema(description = "Lễ tân xử lý; null khi OPEN hoặc khi hệ thống hủy", example = "21")
        Long handledBy,

        @Schema(description = "Thời điểm xử lý hoặc hủy; null khi OPEN", example = "2026-10-27T03:05:00Z")
        Instant handledAt,

        @Schema(description = "Thời điểm tạo", example = "2026-10-27T02:30:00Z")
        Instant createdAt) {
}
