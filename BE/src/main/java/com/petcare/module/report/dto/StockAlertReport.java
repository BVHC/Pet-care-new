package com.petcare.module.report.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** report-v1 {@code StockAlertReport}: Tồn dưới ngưỡng và lô sắp / đã hết hạn tại thời điểm xem (BR-KO-07, BR-BC-03 (5)). */
public record StockAlertReport(
        @Schema(description = "Sản phẩm dưới tồn tối thiểu")
        List<LowStockRow> lowStock,
        @Schema(description = "Lô sắp hết hạn và lô đã hết hạn còn tồn, theo hạn tăng dần")
        List<ExpiringLotRow> expiringLots) {
}
