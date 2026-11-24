package com.petcare.module.inpatient.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Nhập viện (inpatient-v1 {@code AdmitRequest}, #1; BR-LT-13). Client chỉ chọn nguồn (lượt khám <b>hoặc</b> đặt chỗ lưu
 * trú), dịch vụ nội trú và chuồng điều trị. Thú, chi nhánh, đơn giá, người nhập và thời điểm do server lấy từ dòng đã khóa
 * (docs/adr/0149 mục 17), nên không có trong body. Điều kiện "đúng một nguồn" và "có / không có {@code kennelId}" là
 * {@code BR-LT-13} của hợp đồng nên service kiểm, không đặt ở đây.
 */
public record AdmitRequest(
        @Schema(description = "Nhập viện từ lượt khám `IN_PROGRESS` của mình", example = "9001", nullable = true)
        @Positive(message = "Mã lượt khám không hợp lệ")
        Long visitId,

        @Schema(description = "Hoặc chuyển từ đặt chỗ `CHECKED_IN` hoặc `OVERDUE` (BR-LT-13). Đúng một trong hai",
                example = "301", nullable = true)
        @Positive(message = "Mã đặt chỗ lưu trú không hợp lệ")
        Long boardingBookingId,

        @Schema(description = "Dịch vụ loại `INPATIENT`", example = "41", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Vui lòng chọn dịch vụ nội trú")
        @Positive(message = "Mã dịch vụ không hợp lệ")
        Long serviceId,

        @Schema(description = "Chuồng `AVAILABLE`. Bắt buộc với `visitId`; **cấm** với `boardingBookingId` (thú ở nguyên "
                + "chuồng của đặt chỗ, BR-LT-13)", example = "17", nullable = true)
        @Positive(message = "Mã chuồng không hợp lệ")
        Long kennelId) {
}
