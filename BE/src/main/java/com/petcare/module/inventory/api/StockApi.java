package com.petcare.module.inventory.api;

import java.util.List;

/**
 * Owner: inventory (KO) · BE-2. Caller: sales (Order#8), visit (ghi / xóa mũi tiêm).
 * Mọi thay đổi số dư lô ghi kèm StockMovement trong cùng transaction (04 nguyên tắc 5).
 * Xuất FEFO, không bao giờ xuất lô hết hạn (BR-KO-05).
 */
public interface StockApi {

    record SaleLine(Long productId, int quantity) {}

    record Shortage(Long productId, int requested, int available) {}

    /**
     * Order#8 / ST06: khóa lô, kiểm tra tồn khả dụng và trừ FEFO cho mọi dòng, movement SALE nguồn
     * {@code orders/orderId}. Thiếu bất kỳ dòng nào thì không trừ gì và trả về danh sách thiếu; rỗng = đã trừ.
     */
    List<Shortage> issueForSale(Long orderId, Long branchId, List<SaleLine> lines, Long actorId);

    /**
     * Ghi mũi tiêm bước 1: khóa và trả về lô FEFO chưa hết hạn còn đủ {@code quantity}; không có lô nào đủ thì
     * ném lỗi nghiệp vụ. Tách 2 bước vì {@code vaccinations.stock_lot_id} NOT NULL, còn
     * {@code stock_movements.source_id} cần id của mũi tiêm.
     */
    Long lockFefoLot(Long branchId, Long productId, int quantity);

    /** Ghi mũi tiêm bước 2: trừ lô đã khóa, movement VACCINATION nguồn {@code vaccinations/vaccinationId}. */
    void issueForVaccination(Long stockLotId, int quantity, Long vaccinationId, Long actorId);

    /** Xóa mũi tiêm ghi nhầm: hoàn đúng lô, movement VACCINATION_REVERT (BR-KB-04). */
    void revertVaccination(Long stockLotId, int quantity, Long vaccinationId, Long actorId);
}
