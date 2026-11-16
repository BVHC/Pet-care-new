package com.petcare.module.content.dto;

import java.time.Instant;

import com.petcare.module.content.entity.FeedbackTopic;

import io.swagger.v3.oas.annotations.media.Schema;

/** content-v1 {@code Feedback} (#20–22): chi tiết cho SUPER_MANAGER / BRANCH_MANAGER (chỉ đọc, không có trạng thái, ADR-0122). */
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
        @Schema(description = "Gửi lúc", example = "2026-10-23T03:00:00Z")
        Instant createdAt) {
}
