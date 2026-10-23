package com.petcare.module.content.dto;

import java.time.Instant;

import com.petcare.module.content.entity.FeedbackStatus;
import com.petcare.module.content.entity.FeedbackTopic;

import io.swagger.v3.oas.annotations.media.Schema;

/** content-v1 {@code Feedback} (#20–22): chi tiết cho SUPER_MANAGER / BRANCH_MANAGER, gồm ghi chú nội bộ. */
public record FeedbackResponse(
        @Schema(description = "Mã feedback", example = "17")
        Long feedbackId,
        @Schema(description = "Mã khách hàng", example = "42")
        Long customerId,
        @Schema(description = "Họ tên khách", example = "Nguyễn Thị Mai")
        String customerName,
        @Schema(description = "Chủ đề", example = "BRANCH_SERVICE")
        FeedbackTopic topic,
        @Schema(description = "Chi nhánh", example = "2", nullable = true)
        Long branchId,
        @Schema(description = "Tên chi nhánh", example = "PetCare Quận 1", nullable = true)
        String branchName,
        @Schema(description = "Mức hài lòng 1–5", example = "4", nullable = true)
        Integer rating,
        @Schema(description = "Nội dung", example = "Nhân viên tư vấn nhiệt tình, phòng khám sạch sẽ.")
        String content,
        @Schema(description = "Trạng thái xử lý", example = "SEEN")
        FeedbackStatus status,
        @Schema(description = "Mở lần đầu lúc", example = "2026-10-23T03:05:00Z", nullable = true)
        Instant seenAt,
        @Schema(description = "Người đánh dấu đã xử lý", example = "5", nullable = true)
        Long resolvedBy,
        @Schema(description = "Đánh dấu đã xử lý lúc", example = "2026-10-23T04:00:00Z", nullable = true)
        Instant resolvedAt,
        @Schema(description = "Ghi chú nội bộ (khách không thấy)", example = "Đã gọi lại khách xin lỗi.", nullable = true)
        String resolutionNote,
        @Schema(description = "Gửi lúc", example = "2026-10-23T03:00:00Z")
        Instant createdAt) {
}
