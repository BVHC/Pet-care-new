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

    /** Dòng kho (hàng, thuốc — không vaccine) của một Order trong phiếu thu. */
    record OrderSale(Long orderId, List<SaleLine> lines) {}

    /**
     * Order#8 / ST06 cho một phiếu thu (thu gộp nhiều Order, BR-TG-03; docs/adr/0064): khóa lô của <b>hợp</b> sản phẩm
     * mọi Order trong <b>một</b> câu (id lô tăng dần — hai phiếu thu gộp chéo sản phẩm không deadlock), kiểm tổng yêu
     * cầu từng sản phẩm. Thiếu bất kỳ sản phẩm nào thì không trừ gì và trả {@code Shortage(productId, tổng yêu cầu, tồn
     * khả dụng)}; rỗng = đã trừ FEFO lần lượt theo {@code orderId} tăng dần, movement SALE nguồn
     * {@code orders/<orderId>}. Danh sách rỗng / Order không có dòng thì không chạm DB. Thay {@code issueForSale} (chưa
     * có bên gọi) từ 2026-10-21.
     */
    List<Shortage> issueForPayment(Long branchId, List<OrderSale> orders, Long actorId);

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
