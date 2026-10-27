package com.petcare.module.care.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * care-v1 {@code CancelCareTaskRequest} (#4, Care Task#3). Lý do trống hoặc toàn khoảng trắng là {@code BR-TB-05} do
 * service kiểm (xem {@link CompleteCareTaskRequest}); độ dài khớp {@code care_tasks.cancel_reason VARCHAR(300)}.
 */
public record CancelCareTaskRequest(
        @Schema(description = "Lý do hủy, bắt buộc (BR-TB-05)", example = "Khách đã chuyển đi nơi khác.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @Size(max = 300, message = "Lý do tối đa 300 ký tự")
        String reason) {
}
