package com.petcare.module.inpatient.dto;

import java.time.Instant;

import com.petcare.module.inpatient.entity.AdmissionOrderStatus;
import com.petcare.module.inpatient.entity.AdmissionOrderType;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Y lệnh nội trú (inpatient-v1 {@code AdmissionOrder}, #5–#8). Đúng 10 trường theo thứ tự hợp đồng; trường nullable luôn
 * có mặt trong JSON. Không trả người ra / người làm, {@code createdAt}, {@code updatedAt} (hợp đồng không có; docs/adr/0149
 * mục 26).
 */
public record AdmissionOrderResponse(
        @Schema(description = "Mã y lệnh", example = "88")
        Long admissionOrderId,

        @Schema(description = "Ca nội trú", example = "51")
        Long admissionId,

        @Schema(description = "Loại y lệnh", example = "MEDICATION")
        AdmissionOrderType orderType,

        @Schema(description = "Nội dung y lệnh (đã bỏ khoảng trắng đầu / cuối)", example = "Tiêm kháng sinh theo đơn")
        String content,

        @Schema(description = "Giờ thực hiện dự kiến", example = "2026-11-25T01:00:00Z")
        Instant scheduledAt,

        @Schema(description = "Trạng thái (03 #25)", example = "ORDERED")
        AdmissionOrderStatus status,

        @Schema(description = "Dòng đơn thuốc của Visit; chỉ có với `MEDICATION`", example = "7001", nullable = true)
        Long prescriptionItemId,

        @Schema(description = "Thời điểm thực hiện; null khi chưa thực hiện", example = "2026-11-25T01:05:00Z",
                nullable = true)
        Instant doneAt,

        @Schema(description = "Ghi chú thực hiện; null khi chưa thực hiện", example = "Đã tiêm", nullable = true)
        String doneNote,

        @Schema(description = "Lý do hủy; null khi chưa hủy", example = "Thú đã được ăn trước giờ", nullable = true)
        String cancelReason) {
}
