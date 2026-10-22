package com.petcare.module.customer.dto;

import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

/**
 * Body của {@code POST /api/pets/{petId}/mark-deceased} (customer-v1 {@code MarkDeceasedRequest}, UC24; BR-KH-05).
 * Xác nhận 2 bước làm ở giao diện; API yêu cầu {@code confirmed = true} (customer-v1 A3) vì thao tác không hoàn tác được.
 * Ngày mất không ở tương lai và không trước ngày sinh: kiểm ở service bằng bean {@code Clock}.
 */
public record MarkDeceasedRequest(
        @Schema(description = "Ngày mất, không ở tương lai và không trước ngày sinh", example = "2026-09-30")
        @NotNull(message = "Vui lòng nhập ngày mất")
        LocalDate deceasedOn,

        @Schema(description = "Phải true: xác nhận đánh dấu đã mất, không hoàn tác được (A3)", example = "true")
        @NotNull(message = "Vui lòng xác nhận")
        @AssertTrue(message = "Cần xác nhận đánh dấu đã mất (không hoàn tác được)")
        Boolean confirmed) {
}
