package com.petcare.module.inventory.api;

import java.time.Instant;
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

    // ------------------------------------------------------------------------------------------------------------
    // Tầng 3 (T55c, docs/adr/0117, docs/06 §6b hàng 3, 12, 13, 16): placeholder dạng default — ném UnsupportedOperationException
    // tới khi StockQueryService ghi đè; khi đó xóa default (method thành trừu tượng).
    // ------------------------------------------------------------------------------------------------------------

    /** Một biến động của lô ({@code stock_movements}) cho truy vết lô (BR-KO-13); {@code sourceType} là tên bảng nguồn. */
    record LotMovement(Long movementId, String movementType, int quantityDelta, int balanceAfter, String sourceType,
                       Long sourceId, Long createdBy, Instant createdAt) {}

    /** Tồn của một chi nhánh (BR-CN-07): số sản phẩm còn tồn &gt; 0 và tổng số lượng các lô (kể cả lô hết hạn còn số dư). */
    record StockTotals(long productCount, long totalQuantity) {}

    /**
     * BR-BH-08, BR-BH-09: như {@link #availableQuantities} nhưng <b>đã trừ phần đang giữ</b> ({@code stock_reservations}
     * {@code HELD}). Quyết định T54 (08 §G): mọi nơi tính tồn dùng method này; T63 chuyển các nơi đang gọi
     * {@code availableQuantity(ies)} sang đây. Sản phẩm không có lô trả 0 (vẫn có key); tập rỗng → map rỗng. Bản thật
     * trước 17/11 (T63).
     */
    default Map<Long, Integer> findAvailableQuantity(Long branchId, Collection<Long> productIds) {
        throw placeholder("findAvailableQuantity", "T63");
    }

    /**
     * I-5 (07 §11, UC92): trong các sản phẩm đã cho, tập sản phẩm còn tồn khả dụng &gt; 0 ở ít nhất một chi nhánh. Bản thật
     * trước 7/12 (T78).
     */
    default Set<Long> findProductIdsInStockAnywhere(Collection<Long> productIds) {
        throw placeholder("findProductIdsInStockAnywhere", "T78");
    }

    /** BR-KO-13 (UC79): mọi biến động của một lô, theo thời điểm tăng dần. Đọc không khóa. Bản thật trước 3/12 (T74). */
    default List<LotMovement> findMovementsOfLot(Long lotId) {
        throw placeholder("findMovementsOfLot", "T74");
    }

    /** BR-CN-07, BR-CN-08 (UC13): tồn của chi nhánh, chặn đóng cửa khi &gt; 0. Bản thật trước 7/12 (T75). */
    default StockTotals sumQuantityByBranch(Long branchId) {
        throw placeholder("sumQuantityByBranch", "T75");
    }

    private static UnsupportedOperationException placeholder(String method, String task) {
        return new UnsupportedOperationException("StockQueryApi." + method + " chưa cài (placeholder T55c, docs/06 §6b); "
                + "bản thật ở " + task);
    }
}
