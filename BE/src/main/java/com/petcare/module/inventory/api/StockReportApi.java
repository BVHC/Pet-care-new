package com.petcare.module.inventory.api;

import java.time.LocalDate;
import java.util.List;

/**
 * Owner: inventory (KO) · BE-2. Caller: report (UC89), ST08 dùng chung logic. Số liệu tại thời điểm xem,
 * không theo kỳ. {@code branchId} null = toàn chuỗi.
 */
public interface StockReportApi {

    record LowStockRow(Long branchId, Long productId, int available, int minQuantity) {}

    record ExpiringLotRow(Long branchId, Long productId, Long stockLotId, String lotNumber,
                          LocalDate expiryDate, int quantity) {}

    /** BR-KO-07: tồn khả dụng dưới tồn tối thiểu. */
    List<LowStockRow> lowStock(Long branchId);

    /** BR-KO-07: lô còn tồn sẽ hết hạn trong {@code withinDays} ngày [CFG 30], gồm cả lô đã hết hạn. */
    List<ExpiringLotRow> expiringLots(Long branchId, int withinDays);
}
