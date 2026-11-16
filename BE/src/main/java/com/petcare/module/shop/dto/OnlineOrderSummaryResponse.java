package com.petcare.module.shop.dto;

import java.time.Instant;

import com.petcare.module.shop.api.OnlineOrderStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/** shop-v1 {@code OnlineOrderSummary}: một dòng của danh sách đơn online (UC64, UC65). */
public record OnlineOrderSummaryResponse(
        @Schema(example = "21", requiredMode = Schema.RequiredMode.REQUIRED) Long onlineOrderId,
        @Schema(description = "Order bán hàng tương ứng (orders.id), dùng khi tạo yêu cầu trả hàng (returns-v1)",
                example = "340", requiredMode = Schema.RequiredMode.REQUIRED) Long salesOrderId,
        @Schema(example = "DO-2611-00001", requiredMode = Schema.RequiredMode.REQUIRED) String code,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) OnlineOrderStatus status,
        @Schema(example = "Pet Care Quận 1", requiredMode = Schema.RequiredMode.REQUIRED) String branchName,
        @Schema(description = "Số dòng hàng của đơn", example = "2", requiredMode = Schema.RequiredMode.REQUIRED) int itemCount,
        @Schema(description = "VND: tiền hàng + phí vận chuyển", example = "400000", minimum = "0",
                requiredMode = Schema.RequiredMode.REQUIRED) long total,
        @Schema(example = "2026-11-15T10:00:00Z", requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt) {
}
