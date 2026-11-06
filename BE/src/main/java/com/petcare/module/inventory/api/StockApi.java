package com.petcare.module.inventory.api;

import java.time.LocalDate;
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

    // ------------------------------------------------------------------------------------------------------------
    // Tầng 3 (T55c, docs/adr/0117, docs/06 §6b hàng 3, 8, 12): placeholder dạng default — ném UnsupportedOperationException
    // tới khi StockIssueService ghi đè; khi đó xóa default (method thành trừu tượng).
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Tham chiếu đơn online cho giữ / nhả / xuất / hoàn hàng: {@code onlineOrderId} = {@code online_orders.id} (khóa của
     * {@code stock_reservations}); {@code orderId} = {@code orders.id} của Order nguồn {@code ONLINE} (nguồn của movement,
     * {@code orders/<orderId>}).
     */
    record OrderRef(Long onlineOrderId, Long orderId) {}

    /** Một lô cụ thể đã lấy hàng cho phiếu chuyển kho (BR-KO-08); bên gọi ghi vào {@code stock_transfer_lines}. */
    record TransferredLot(Long productId, Long sourceLotId, int quantity, String lotNumber, LocalDate expiryDate) {}

    /**
     * BR-BH-08, BR-BH-09 (08 §B): giữ hàng tại chi nhánh cho đơn online ({@code stock_reservations} {@code HELD}), kiểm
     * theo tồn khả dụng đã trừ phần đang giữ ({@link StockQueryApi#findAvailableQuantity}). Thiếu bất kỳ dòng nào thì
     * không giữ gì và trả {@link Shortage} từng dòng (cùng kiểu {@link #issueForPayment}); rỗng = đã giữ. Bản thật
     * trước 17/11 (T63).
     */
    default List<Shortage> reserve(Long branchId, OrderRef orderRef, List<SaleLine> lines) {
        throw placeholder("reserve", "T63");
    }

    /** Đơn hàng online#3, #4 (hủy / quá hạn thanh toán): nhả phần giữ, {@code HELD} → {@code RELEASED}. Bản thật trước 17/11 (T63). */
    default void release(OrderRef orderRef) {
        throw placeholder("release", "T63");
    }

    /**
     * Đơn hàng online#2 (BR-BH-09): xuất FEFO từ phần giữ trong cùng transaction với {@code PAID}, movement {@code SALE}
     * nguồn {@code orders/<orderId>}; {@code HELD} → {@code CONSUMED}. Bản thật trước 17/11 (T63), nối với thanh toán ở T65.
     */
    default void consumeReservation(OrderRef orderRef, Long actorId) {
        throw placeholder("consumeReservation", "T63");
    }

    /**
     * Đơn hàng online#10, #13 (BR-BH-09): hủy sau thanh toán mà chưa bàn giao, hoàn hàng về <b>đúng lô đã xuất</b>. Loại
     * movement của việc hoàn chưa có trong đặc tả (ADR-0117, TBD-1). Bản thật trước 17/11 (T63).
     */
    default void restock(OrderRef orderRef, Long actorId) {
        throw placeholder("restock", "T63");
    }

    /**
     * BR-BH-14 (08 §D) {@code restock = true}: nhập lại {@code quantity} vào lô gốc của dòng Order. Loại movement: TBD
     * như {@link #restock}. Bản thật trước 30/11 (T73).
     */
    default void restockToOriginalLot(Long orderLineId, int quantity, Long actorId) {
        throw placeholder("restockToOriginalLot", "T73");
    }

    /**
     * BR-BH-14 {@code restock = false}: loại bỏ hàng trả bằng điều chỉnh giảm lý do {@code RETURN_DISCARD}
     * ({@code stock_adjustments}). Bản thật trước 30/11 (T73).
     */
    default void discardReturned(Long orderLineId, int quantity, Long actorId) {
        throw placeholder("discardReturned", "T73");
    }

    /**
     * BR-KO-08, FSM #18 dòng 1: trừ ngay khỏi lô nguồn theo FEFO, movement {@code TRANSFER_OUT} nguồn
     * {@code stock_transfers/<transferId>}; trả lô cụ thể đã lấy. Thiếu tồn khả dụng thì lỗi nghiệp vụ 400
     * {@code BR-KO-08}. Bản thật trước 3/12 (T74).
     */
    default List<TransferredLot> transferOut(Long sourceBranchId, Long transferId, List<SaleLine> lines, Long actorId) {
        throw placeholder("transferOut", "T74");
    }

    /**
     * BR-KO-09, FSM #18 dòng 2: tạo lô ở chi nhánh đích với đúng số lô, hạn dùng, số lượng; movement {@code TRANSFER_IN}.
     * Trả id lô đích theo đúng thứ tự {@code lots}. Bản thật trước 3/12 (T74).
     */
    default List<Long> transferIn(Long destBranchId, Long transferId, List<TransferredLot> lots, Long actorId) {
        throw placeholder("transferIn", "T74");
    }

    /** BR-KO-10, FSM #18 dòng 3: hoàn đúng lô nguồn, movement {@code TRANSFER_CANCEL}. Bản thật trước 3/12 (T74). */
    default void transferCancel(Long sourceBranchId, Long transferId, List<TransferredLot> lots, Long actorId) {
        throw placeholder("transferCancel", "T74");
    }

    private static UnsupportedOperationException placeholder(String method, String task) {
        return new UnsupportedOperationException("StockApi." + method + " chưa cài (placeholder T55c, docs/06 §6b); "
                + "bản thật ở " + task);
    }
}
