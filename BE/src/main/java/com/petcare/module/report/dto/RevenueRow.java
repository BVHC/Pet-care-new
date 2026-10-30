package com.petcare.module.report.dto;

import com.petcare.module.sales.api.OrderSource;
import io.swagger.v3.oas.annotations.media.Schema;

/** report-v1 {@code RevenueRow}: Doanh thu một chi nhánh theo một nguồn (BR-BC-02). */
public record RevenueRow(
        @Schema(description = "Chi nhánh của Order", example = "2")
        Long branchId,
        @Schema(description = "Tên chi nhánh", example = "PetCare Quận 1")
        String branchName,
        @Schema(description = "Nguồn Order", example = "VISIT")
        OrderSource source,
        @Schema(description = "Số Order đã thanh toán trong kỳ", example = "12")
        int orderCount,
        @Schema(description = "Tổng giá trị Order (VND, theo đơn giá đã snapshot)", example = "3450000")
        long amount) {
}
