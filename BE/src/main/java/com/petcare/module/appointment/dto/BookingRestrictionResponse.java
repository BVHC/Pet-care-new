package com.petcare.module.appointment.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Một lần hạn chế đặt online đang hiệu lực (appointment-v1 schema {@code BookingRestriction}, BR-LH-09). Chỉ dựng bởi
 * {@code BookingRestrictionMapper}. Không lộ {@code lifted_at}, {@code lifted_by}, {@code lift_reason}: chỉ dòng đang hiệu
 * lực được trả nên các cột này luôn null; khách và nhân viên dùng chung DTO vì không có trường chỉ nhân viên được xem
 * (convention 02; docs/adr/0055).
 */
public record BookingRestrictionResponse(
        @Schema(description = "Mã lần hạn chế", example = "41")
        Long restrictionId,

        @Schema(description = "Hồ sơ khách bị hạn chế", example = "88")
        Long customerId,

        @Schema(description = "Bắt đầu: lúc xảy ra vi phạm làm đủ ngưỡng", example = "2026-10-15T02:00:00Z")
        Instant startsAt,

        @Schema(description = "Hết hạn chế: startsAt + 30 ngày [CFG]; không đổi khi gỡ sớm",
                example = "2026-11-14T02:00:00Z")
        Instant endsAt,

        @Schema(description = "Số NO_SHOW + hủy muộn đã đếm khi tạo hạn chế (docs/adr/0049)", example = "3")
        int violationCount) {
}
