package com.petcare.module.workforce.dto;

import java.time.Instant;
import java.time.LocalDate;

import com.petcare.module.workforce.entity.ShiftRequestStatus;
import com.petcare.module.workforce.entity.ShiftRequestType;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Yêu cầu nghỉ / đổi ca (workforce-v1 {@code ShiftRequest}). Đúng 15 trường theo thứ tự hợp đồng; sáu trường nullable
 * luôn có mặt trong JSON (Jackson không bỏ null). Không trả {@code updatedAt}, chi nhánh hay trạng thái phân ca. Tên
 * người gửi và tên ca được ghép ở service (docs/adr/0138 bước 6).
 * <p>
 * <b>Tên ca là tên hiện tại, không phải ảnh chụp:</b> {@code shift_requests} không lưu ca gốc của phân ca, nên
 * {@code currentShiftName} là ca <i>đang</i> gắn với phân ca (nợ D074).
 */
public record ShiftRequestResponse(
        @Schema(description = "Mã yêu cầu", example = "15")
        Long shiftRequestId,

        @Schema(description = "Phân ca được yêu cầu nghỉ / đổi", example = "31")
        Long shiftAssignmentId,

        @Schema(description = "Nhân viên gửi yêu cầu", example = "12")
        Long requesterId,

        @Schema(description = "Họ tên người gửi (kể cả người đã nghỉ, để xem lịch sử)", example = "Nguyễn Văn An")
        String requesterName,

        @Schema(description = "Ngày làm của phân ca, yyyy-MM-dd", example = "2026-11-24", type = "string",
                format = "date")
        LocalDate workDate,

        @Schema(description = "Tên ca hiện đang gắn với phân ca (không phải ảnh chụp lúc gửi)", example = "Ca sáng")
        String currentShiftName,

        @Schema(description = "LEAVE = nghỉ ca đó; SWAP = đổi sang ca khác cùng ngày", example = "SWAP")
        ShiftRequestType type,

        @Schema(description = "Ca đích, chỉ có với SWAP; null với LEAVE", example = "8", nullable = true)
        Long targetShiftDefinitionId,

        @Schema(description = "Tên hiện tại của ca đích; null với LEAVE", example = "Ca chiều", nullable = true)
        String targetShiftName,

        @Schema(description = "Lý do của nhân viên", example = "Có việc gia đình đột xuất")
        String reason,

        @Schema(description = "Trạng thái yêu cầu (03 #11)", example = "PENDING")
        ShiftRequestStatus status,

        @Schema(description = "Người duyệt hoặc từ chối; null khi còn chờ, đã rút, hoặc bị hệ thống từ chối",
                example = "5", nullable = true)
        Long decidedBy,

        @Schema(description = "Lý do từ chối; null khi chưa bị từ chối", example = "Thiếu người trực ca chiều",
                nullable = true)
        String decisionNote,

        @Schema(description = "Thời điểm yêu cầu được xử lý; null khi còn chờ", example = "2026-11-20T03:05:00Z",
                nullable = true)
        Instant closedAt,

        @Schema(description = "Thời điểm gửi", example = "2026-11-20T02:30:00Z")
        Instant createdAt) {
}
