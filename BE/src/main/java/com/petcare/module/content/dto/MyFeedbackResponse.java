package com.petcare.module.content.dto;

import java.time.Instant;

import com.petcare.module.content.entity.FeedbackStatus;
import com.petcare.module.content.entity.FeedbackTopic;

import io.swagger.v3.oas.annotations.media.Schema;

/** content-v1 {@code MyFeedback} (#18, #19): khách chỉ thấy trạng thái, không thấy ghi chú nội bộ (BR-DG-04). */
public record MyFeedbackResponse(
        @Schema(description = "Mã feedback", example = "17")
        Long feedbackId,
        @Schema(description = "Chủ đề", example = "BRANCH_SERVICE")
        FeedbackTopic topic,
        @Schema(description = "Chi nhánh", example = "2", nullable = true)
        Long branchId,
        @Schema(description = "Mức hài lòng 1–5", example = "4", nullable = true)
        Integer rating,
        @Schema(description = "Nội dung", example = "Nhân viên tư vấn nhiệt tình, phòng khám sạch sẽ.")
        String content,
        @Schema(description = "Trạng thái xử lý", example = "NEW")
        FeedbackStatus status,
        @Schema(description = "Gửi lúc", example = "2026-10-23T03:00:00Z")
        Instant createdAt) {
}
