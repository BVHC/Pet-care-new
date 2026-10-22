package com.petcare.module.boarding.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** Kết quả chuyển bảo trì (boarding-v1 {@code MaintenanceResult}, #5): cảnh báo, không tự hủy (BR-LT-01). */
public record MaintenanceResultResponse(
        @Schema(description = "Chuồng sau khi chuyển bảo trì")
        KennelResponse kennel,

        @Schema(description = "Đặt chỗ BOOKED thiếu chỗ ít nhất một đêm sau khi giảm sức chứa; rỗng nếu không có")
        List<AffectedBookingBriefResponse> shortageBookings) {
}
