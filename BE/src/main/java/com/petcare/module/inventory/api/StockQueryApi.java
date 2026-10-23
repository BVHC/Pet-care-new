package com.petcare.module.inventory.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /**
     * Như {@link #branchIdsWithAvailableStock(Long)} cho nhiều sản phẩm một lần (thêm 2026-10-23, T32): key đủ mọi id
     * (danh sách rỗng nếu hết hàng), chi nhánh tăng dần, không lọc trạng thái chi nhánh (bên gọi lọc); tập rỗng → map rỗng.
     */
    Map<Long, List<Long>> branchIdsWithAvailableStock(Collection<Long> productIds);

    /** Sản phẩm còn tồn khả dụng tại chi nhánh (BR-CK-03, lọc `branchId` ở trang sản phẩm công khai). */
    Set<Long> productIdsWithAvailableStock(Long branchId);

    /** Còn tồn ở bất kỳ chi nhánh nào → không tắt cờ quản lý hạn dùng (BR-SP-05). */
    boolean hasStockAnywhere(Long productId);
}
