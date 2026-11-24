package com.petcare.module.inpatient.dto;

import java.time.Instant;

import com.petcare.module.inpatient.entity.AdmissionStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Một dòng của danh sách ca nội trú (inpatient-v1 {@code AdmissionSummary}, #2). Đúng 8 trường theo thứ tự hợp đồng.
 * {@code kennelCode} là chuồng điều trị (nhập từ khám) hoặc chuồng của đặt chỗ (nhập từ lưu trú), mã hiện tại
 * (docs/adr/0149 mục 19).
 */
public record AdmissionSummaryResponse(
        @Schema(description = "Mã ca nội trú", example = "51")
        Long admissionId,

        @Schema(description = "Mã ca hiển thị", example = "NT-2611-00007")
        String code,

        @Schema(description = "Tên hiện tại của thú", example = "Mochi")
        String petName,

        @Schema(description = "Trạng thái ca (03 #24)", example = "ADMITTED")
        AdmissionStatus status,

        @Schema(description = "Thời điểm nhập viện", example = "2026-11-23T03:15:30.123456Z")
        Instant admittedAt,

        @Schema(description = "Mã chuồng thú đang nằm (chuồng điều trị hoặc chuồng của đặt chỗ)", example = "C-03",
                nullable = true)
        String kennelCode,

        @Schema(description = "Số ngày tính tiền tới hôm nay (hoặc tới ngày xuất viện), giờ Việt Nam (BR-LT-14)",
                example = "2")
        Integer billedDays,

        @Schema(description = "`billedDays` × giá theo ngày đã chốt; tiền tạm tính", example = "500000")
        Long estimatedAmount) {
}
