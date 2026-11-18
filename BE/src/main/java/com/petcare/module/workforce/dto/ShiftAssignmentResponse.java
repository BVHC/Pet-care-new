package com.petcare.module.workforce.dto;

import java.time.LocalDate;
import java.time.LocalTime;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.petcare.module.workforce.entity.ShiftAssignmentSource;
import com.petcare.module.workforce.entity.ShiftAssignmentStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Phân ca (workforce-v1 {@code ShiftAssignment}). Đúng 13 trường theo thứ tự hợp đồng; {@code reason} và
 * {@code pendingRequestId} có thể {@code null} (Jackson không bỏ null). Không trả {@code createdAt} / {@code updatedAt} /
 * {@code statusChanged*}. Tên nhân viên, tên và giờ ca được ghép ở service (docs/adr/0138).
 */
public record ShiftAssignmentResponse(
        @Schema(description = "Mã phân ca", example = "31")
        Long shiftAssignmentId,

        @Schema(description = "Chi nhánh của phân ca", example = "1")
        Long branchId,

        @Schema(description = "Nhân viên được phân ca", example = "12")
        Long staffId,

        @Schema(description = "Họ tên nhân viên (kể cả người đã nghỉ, để xem lịch sử)", example = "Nguyễn Văn An")
        String staffName,

        @Schema(description = "Ca làm việc", example = "7")
        Long shiftDefinitionId,

        @Schema(description = "Tên ca hiện tại (ca được phép đổi tên nên không phải ảnh chụp)", example = "Ca sáng")
        String shiftName,

        @Schema(description = "Giờ bắt đầu của ca, HH:mm", example = "08:00", type = "string")
        @JsonFormat(pattern = "HH:mm")
        LocalTime startTime,

        @Schema(description = "Giờ kết thúc của ca, HH:mm", example = "12:00", type = "string")
        @JsonFormat(pattern = "HH:mm")
        LocalTime endTime,

        @Schema(description = "Ngày làm, yyyy-MM-dd", example = "2026-11-24", type = "string", format = "date")
        LocalDate workDate,

        @Schema(description = "Trạng thái phân ca (03 #12)", example = "SCHEDULED")
        ShiftAssignmentStatus status,

        @Schema(description = "SELF = nhân viên tự đăng ký (BR-NS-11); MANAGER = BRANCH_MANAGER phân hộ (BR-NS-02)",
                example = "MANAGER")
        ShiftAssignmentSource source,

        @Schema(description = "Lý do vắng hoặc hủy; null khi chưa có", example = "Nhân viên báo ốm", nullable = true)
        String reason,

        @Schema(description = "Yêu cầu PENDING của phân ca nếu có (BR-NS-04); null khi không có", example = "15",
                nullable = true)
        Long pendingRequestId) {
}
