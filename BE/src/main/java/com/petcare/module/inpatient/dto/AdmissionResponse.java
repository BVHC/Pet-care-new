package com.petcare.module.inpatient.dto;

import java.time.Instant;

import com.petcare.module.inpatient.entity.AdmissionStatus;
import com.petcare.module.inpatient.entity.DischargeOutcome;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Ca nội trú (inpatient-v1 {@code Admission}, #1 và #3). Đúng 19 trường theo thứ tự hợp đồng; trường nullable luôn có mặt
 * trong JSON (Jackson không bỏ null). {@code unitPrice} là giá đã chốt lúc nhập viện (BR-BH-03); {@code petName},
 * {@code serviceName} là tên hiện tại (A6). {@code billedDays}, {@code estimatedAmount} tính lúc đọc, không lưu
 * (BR-LT-14). Không trả người nhập / xuất, {@code createdAt}, {@code updatedAt} (docs/adr/0149 mục 17).
 */
public record AdmissionResponse(
        @Schema(description = "Mã ca nội trú", example = "51")
        Long admissionId,

        @Schema(description = "Mã ca hiển thị", example = "NT-2611-00007")
        String code,

        @Schema(description = "Lượt chứa mọi khoản phí của ca (lượt khám, hoặc Visit điều trị khi nhập từ lưu trú)",
                example = "9001")
        Long visitId,

        @Schema(description = "Thú cưng", example = "12")
        Long petId,

        @Schema(description = "Tên hiện tại của thú", example = "Mochi")
        String petName,

        @Schema(description = "Chi nhánh của ca", example = "3")
        Long branchId,

        @Schema(description = "Đặt chỗ lưu trú khi nhập từ lưu trú; null khi nhập từ lượt khám", example = "301",
                nullable = true)
        Long boardingBookingId,

        @Schema(description = "Chuồng điều trị khi nhập từ lượt khám; null khi nhập từ lưu trú", example = "17",
                nullable = true)
        Long kennelId,

        @Schema(description = "Dịch vụ nội trú", example = "41")
        Long serviceId,

        @Schema(description = "Tên hiện tại của dịch vụ", example = "Nội trú điều trị")
        String serviceName,

        @Schema(description = "Giá theo ngày, chốt lúc nhập viện", example = "250000")
        Long unitPrice,

        @Schema(description = "Trạng thái ca (03 #24)", example = "ADMITTED")
        AdmissionStatus status,

        @Schema(description = "Thời điểm nhập viện", example = "2026-11-23T03:15:30.123456Z")
        Instant admittedAt,

        @Schema(description = "Số ngày tính tiền = ngày xuất (hoặc hôm nay nếu `ADMITTED`) − ngày nhập + 1, giờ Việt Nam; "
                + "tính khi đọc, không lưu (BR-LT-14)", example = "2")
        Integer billedDays,

        @Schema(description = "`billedDays` × `unitPrice`; tiền tạm tính, dòng Order chỉ có sau xuất viện",
                example = "500000")
        Long estimatedAmount,

        @Schema(description = "Kết cục xuất viện; null khi chưa xuất viện", example = "HOME", nullable = true)
        DischargeOutcome dischargeOutcome,

        @Schema(description = "Tóm tắt điều trị; null khi chưa xuất viện", example = "Hết sốt, ăn uống bình thường",
                nullable = true)
        String dischargeSummary,

        @Schema(description = "Thời điểm xuất viện; null khi chưa xuất viện", example = "2026-11-25T02:00:00Z",
                nullable = true)
        Instant dischargedAt,

        @Schema(description = "Dòng dịch vụ nội trú thêm vào Order lúc xuất viện; null khi chưa xuất viện",
                example = "7301", nullable = true)
        Long orderLineId) {
}
