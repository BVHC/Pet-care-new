package com.petcare.module.boarding.dto;

import com.petcare.module.boarding.entity.KennelStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/** Chuồng (boarding-v1 {@code Kennel}). */
public record KennelResponse(
        @Schema(description = "Mã chuồng", example = "12")
        Long kennelId,

        @Schema(description = "Chi nhánh", example = "1")
        Long branchId,

        @Schema(description = "Loại chuồng (dịch vụ nhóm BOARDING)", example = "5")
        Long kennelTypeId,

        @Schema(description = "Tên loại chuồng", example = "Chuồng chó nhỏ")
        String kennelTypeName,

        @Schema(description = "Mã chuồng trong chi nhánh (BR-LT-01)", example = "C-01")
        String code,

        @Schema(description = "Trạng thái chuồng", example = "AVAILABLE")
        KennelStatus status,

        @Schema(description = "Ghi chú", example = "Gần cửa sổ")
        String note,

        @Schema(description = "Đặt chỗ đang ở chuồng này (CHECKED_IN / OVERDUE); null nếu trống", example = "88")
        Long currentBookingId) {
}
