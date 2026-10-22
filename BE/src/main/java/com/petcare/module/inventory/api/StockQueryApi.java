package com.petcare.module.inventory.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Owner: inventory (KO) · BE-2. Tồn khả dụng = tổng các lô chưa hết hạn (BR-KO-01). */
public interface StockQueryApi {

    /** BR-BH-04 (cảnh báo khi thêm dòng bán lẻ), BR-KB-03 (kê đơn), BR-KB-04 (chọn vaccine). */
    int availableQuantity(Long branchId, Long productId);

    /**
     * Tồn khả dụng của nhiều sản phẩm một lần (một câu SQL), cùng định nghĩa với {@link #availableQuantity}: sản phẩm
     * không có lô trả 0 (vẫn có key); tập rỗng trả map rỗng, không chạm DB. Xem trước phiếu thu (docs/adr/0064).
     */
    Map<Long, Integer> availableQuantities(Long branchId, Collection<Long> productIds);

    /** Chi nhánh còn hàng, không trả số lượng (BR-CK-03). */
    List<Long> branchIdsWithAvailableStock(Long productId);

    /** Còn tồn ở bất kỳ chi nhánh nào → không tắt cờ quản lý hạn dùng (BR-SP-05). */
    boolean hasStockAnywhere(Long productId);
}
