package com.petcare.module.sales.dto;

import java.time.Instant;
import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.petcare.module.sales.entity.CashierShiftStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Ca thu ngân (sales-v1 {@code CashierShift}, #16–#21). {@code isAfterHours} cần {@code @JsonProperty} để JSON giữ đúng
 * tên của hợp đồng. Số tiền là VND; {@code difference} chỉ có sau đối soát và có thể âm.
 */
public record CashierShiftResponse(
        @Schema(description = "Mã ca", example = "12")
        Long shiftId,

        @Schema(description = "Chi nhánh", example = "2")
        Long branchId,

        @Schema(description = "Lễ tân chủ ca", example = "7")
        Long cashierId,

        @Schema(description = "Họ tên lễ tân chủ ca", example = "Trần Thị Bình")
        String cashierName,

        @Schema(description = "Ca ngoài giờ thu tiền cấp cứu (BR-CN-05)", example = "false")
        @JsonProperty("isAfterHours") boolean isAfterHours,

        @Schema(description = "Ngày mở ca (giờ Việt Nam); ca ngoài giờ qua nửa đêm vẫn thuộc ngày này",
                example = "2026-10-21")
        LocalDate businessDate,

        @Schema(description = "Trạng thái", example = "OPEN")
        CashierShiftStatus status,

        @Schema(description = "Thời điểm mở ca (UTC)", example = "2026-10-21T01:00:00Z")
        Instant openedAt,

        @Schema(description = "Thời điểm chốt ca (UTC)", example = "2026-10-21T11:00:00Z")
        Instant closedAt,

        @Schema(description = "Ca do hệ thống tự chốt (ST13)", example = "false")
        boolean autoClosed,

        @Schema(description = "Tiền mặt hệ thống ghi nhận lúc chốt, VND", example = "500000")
        Long expectedCash,

        @Schema(description = "Chuyển khoản hệ thống ghi nhận lúc chốt, VND", example = "200000")
        Long expectedTransfer,

        @Schema(description = "Tiền mặt thực đếm, VND; trống khi ca tự chốt chưa đối soát", example = "480000")
        Long countedCash,

        @Schema(description = "countedCash − expectedCash (VND), có thể âm; chỉ có sau đối soát", example = "-20000")
        Long difference,

        @Schema(description = "Ghi chú đối soát", example = "Thiếu 20.000đ, đã hỏi lễ tân")
        String reconcileNote,

        @Schema(description = "Người đối soát (BRANCH_MANAGER)", example = "4")
        Long reconciledBy,

        @Schema(description = "Thời điểm đối soát (UTC)", example = "2026-10-21T12:00:00Z")
        Instant reconciledAt,

        @Schema(description = "Số phiếu thu trong ca", example = "14")
        int paymentCount) {
}
