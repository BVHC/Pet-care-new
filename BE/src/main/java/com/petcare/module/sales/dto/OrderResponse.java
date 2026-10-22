package com.petcare.module.sales.dto;

import java.time.Instant;
import java.util.List;

import com.petcare.module.sales.api.OrderSource;
import com.petcare.module.sales.entity.OrderCancelType;
import com.petcare.module.sales.entity.OrderStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/** Order (sales-v1 {@code Order}). Chưa trả tiền thì {@code deliveryAllowed = false} (BR-BH-06). */
public record OrderResponse(
        @Schema(description = "Mã Order", example = "101")
        Long orderId,

        @Schema(description = "Số Order liên tục theo tháng", example = "DH-2610-00001")
        String code,

        @Schema(description = "Nguồn Order", example = "VISIT")
        OrderSource source,

        @Schema(description = "Trạng thái", example = "OPEN")
        OrderStatus status,

        @Schema(description = "Khách hàng giữ tại thời điểm tạo, không đổi khi chuyển chủ (BR-KH-08)", example = "5")
        Long customerId,

        @Schema(description = "Họ tên khách", example = "Nguyễn Văn An")
        String customerName,

        @Schema(description = "Chi nhánh", example = "2")
        Long branchId,

        @Schema(description = "Lượt khám của Order nguồn VISIT", example = "61")
        Long visitId,

        @Schema(description = "Đặt chỗ lưu trú của Order nguồn BOARDING", example = "9")
        Long boardingBookingId,

        @Schema(description = "Tổng tiền các dòng, VND", example = "150000")
        long totalAmount,

        @Schema(description = "Các dòng theo thứ tự thêm")
        List<OrderLineResponse> lines,

        @Schema(description = "Thời điểm tạo (UTC)", example = "2026-10-17T03:00:00Z")
        Instant createdAt,

        @Schema(description = "Thời điểm chốt chờ thu tiền (UTC)", example = "2026-10-17T04:00:00Z")
        Instant pendingAt,

        @Schema(description = "Thời điểm thu tiền (UTC)", example = "2026-10-17T04:10:00Z")
        Instant paidAt,

        @Schema(description = "Phiếu thu, có khi PAID", example = "33")
        Long paymentId,

        @Schema(description = "Loại hủy, có khi CANCELLED", example = "VISIT_CANCELLED")
        OrderCancelType cancelType,

        @Schema(description = "Lý do hủy, bắt buộc khi hủy từ PENDING", example = "Khách bỏ về không thanh toán")
        String cancelReason,

        @Schema(description = "Thời điểm hủy (UTC)", example = "2026-10-17T04:20:00Z")
        Instant cancelledAt,

        @Schema(description = "Chỉ true khi PAID: được giao hàng / thú (BR-BH-06)", example = "false")
        boolean deliveryAllowed) {
}
