package com.petcare.module.workforce.dto;

import java.time.Instant;
import java.time.LocalDate;

import com.petcare.module.workforce.entity.ShiftRequestStatus;
import com.petcare.module.workforce.entity.ShiftRequestType;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Yêu cầu nghỉ / đổi ca (workforce-v1 {@code ShiftRequest}). Đúng 15 trường theo thứ tự hợp đồng; sáu trường nullable
 * luôn có mặt trong JSON (Jackson không bỏ null). Không trả {@code updatedAt}, chi nhánh hay trạng thái phân ca. Tên
 * người gửi và tên ca được ghép ở service (docs/adr/0149 bước 6).
 * <p>
 * {@code currentShiftName} là ca của phân ca <b>lúc gửi</b> ({@code shift_requests.current_shift_definition_id}, V60):
 * sau khi duyệt {@code SWAP} phân ca đã ở ca đích nhưng yêu cầu vẫn nêu ca gốc. Tên ca (cả ca gốc lẫn ca đích) là
 * tên <i>hiện tại</i> của ca đó, không phải ảnh chụp tên (ADR-0150 mục 5).
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

        @Schema(description = "Tên ca của phân ca lúc gửi yêu cầu (với SWAP đã duyệt là ca trước khi đổi)",
                example = "Ca sáng")
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
