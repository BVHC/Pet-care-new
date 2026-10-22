package com.petcare.module.boarding.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/** Hủy đặt chỗ (boarding-v1 {@code CancelBookingRequest}, #12); lý do không bắt buộc (A2). */
public record CancelBookingRequest(
        @Schema(description = "Lý do hủy", example = "Đổi lịch công tác")
        @Size(max = 300, message = "Lý do tối đa 300 ký tự")
        String reason) {
}
