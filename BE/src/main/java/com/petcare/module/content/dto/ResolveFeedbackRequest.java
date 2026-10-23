package com.petcare.module.content.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** content-v1 {@code ResolveFeedbackRequest} (#22). Ghi chú trống hoặc toàn khoảng trắng là {@code BR-DG-04} (service). */
public record ResolveFeedbackRequest(
        @Schema(description = "Ghi chú xử lý nội bộ, bắt buộc (BR-DG-04)", example = "Đã gọi lại khách xin lỗi.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Vui lòng nhập ghi chú xử lý")
        @Size(max = 2000, message = "Ghi chú tối đa 2000 ký tự")
        String resolutionNote) {
}
