package com.petcare.module.shop.dto;

import java.time.Instant;
import java.util.List;

import com.petcare.module.shop.api.OnlineOrderStatus;
import com.petcare.module.shop.api.RefundStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/** shop-v1 {@code OnlineOrder}. {@code refundStatus} chỉ khác null khi có hoàn tiền (task returns): đơn mới đặt luôn null. */
public record OnlineOrderResponse(
        @Schema(example = "21", requiredMode = Schema.RequiredMode.REQUIRED) Long onlineOrderId,
        @Schema(description = "Order bán hàng tương ứng (orders.id)", example = "340",
                requiredMode = Schema.RequiredMode.REQUIRED) Long salesOrderId,
        @Schema(example = "DO-2611-00001", requiredMode = Schema.RequiredMode.REQUIRED) String code,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) OnlineOrderStatus status,
        @Schema(example = "3", requiredMode = Schema.RequiredMode.REQUIRED) Long branchId,
        @Schema(example = "Pet Care Quận 1", requiredMode = Schema.RequiredMode.REQUIRED) String branchName,
        @Schema(example = "Nguyễn Văn An", requiredMode = Schema.RequiredMode.REQUIRED) String receiverName,
        @Schema(example = "0901234567", requiredMode = Schema.RequiredMode.REQUIRED) String receiverPhone,
        @Schema(example = "12 Lê Lợi, P. Bến Nghé, TP.HCM", requiredMode = Schema.RequiredMode.REQUIRED) String shipAddress,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<OnlineOrderLineResponse> lines,
        @Schema(description = "VND", example = "370000", minimum = "0",
                requiredMode = Schema.RequiredMode.REQUIRED) long subtotal,
        @Schema(description = "VND", example = "30000", minimum = "0",
                requiredMode = Schema.RequiredMode.REQUIRED) long shippingFee,
        @Schema(description = "VND", example = "400000", minimum = "0",
                requiredMode = Schema.RequiredMode.REQUIRED) long total,
        @Schema(example = "Giao hàng nhanh", nullable = true) String carrier,
        @Schema(example = "GHN123456", nullable = true) String trackingCode,
        @Schema(example = "0", requiredMode = Schema.RequiredMode.REQUIRED) int redeliveryCount,
        @Schema(example = "2026-11-16T10:00:00Z", requiredMode = Schema.RequiredMode.REQUIRED) Instant paymentDueAt,
        @Schema(example = "2026-11-15T11:00:00Z", nullable = true) Instant paidAt,
        @Schema(example = "2026-11-16T09:00:00Z", nullable = true) Instant shippedAt,
        @Schema(example = "2026-11-18T09:00:00Z", nullable = true) Instant deliveredAt,
        @Schema(example = "2026-11-25T09:00:00Z", nullable = true) Instant completedAt,
        @Schema(example = "2026-11-15T12:00:00Z", nullable = true) Instant cancelledAt,
        @Schema(example = "Khách đổi ý", nullable = true) String cancelReason,
        @Schema(example = "2026-11-15T10:00:00Z", requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt,
        @Schema(description = "Trạng thái hoàn tiền gần nhất; null khi chưa có hoàn tiền", example = "SUCCEEDED",
                nullable = true) RefundStatus refundStatus) {
}
