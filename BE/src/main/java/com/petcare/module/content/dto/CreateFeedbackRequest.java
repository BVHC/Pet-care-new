package com.petcare.module.content.dto;

import com.petcare.module.content.entity.FeedbackTopic;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * content-v1 {@code CreateFeedbackRequest} (#18). Độ dài nội dung, điểm 1–5, chi nhánh bắt buộc khi
 * {@code BRANCH_SERVICE} do service kiểm để trả {@code BR-DG-01} (hợp đồng gắn mã rule cho cả ba).
 */
public record CreateFeedbackRequest(
        @Schema(description = "Chủ đề", example = "BRANCH_SERVICE", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Vui lòng chọn chủ đề")
        FeedbackTopic topic,
        @Schema(description = "Chi nhánh; bắt buộc khi BRANCH_SERVICE", example = "2", nullable = true)
        @Positive(message = "Mã chi nhánh phải là số dương")
        Long branchId,
        @Schema(description = "Mức hài lòng 1–5", example = "4", nullable = true)
        Integer rating,
        @Schema(description = "Nội dung 10–2000 ký tự", example = "Nhân viên tư vấn nhiệt tình, phòng khám sạch sẽ.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Vui lòng nhập nội dung")
        String content) {
}
