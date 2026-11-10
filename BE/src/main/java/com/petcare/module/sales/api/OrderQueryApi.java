package com.petcare.module.sales.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Owner: sales (BH) · BE-2. */
public interface OrderQueryApi {

    /** BR-TK-19: hồ sơ online đã phát sinh Order thì không liên kết được. */
    boolean existsByCustomer(Long customerId);

    /**
     * BR-KH-08: thú có Order PENDING (nguồn VISIT hoặc BOARDING) thì không chuyển chủ.
     * Sales tự lấy visitId / bookingId của thú qua VisitQueryApi, BoardingQueryApi.
     */
    boolean hasPendingOrderForPet(Long petId);

    record UnpaidOrderRef(Long orderId, Long branchId) {}

    /** Order OPEN/PENDING của khách — việc dở khi khóa tài khoản khách (BR-QT-11, Q2). */
    List<UnpaidOrderRef> findUnpaidOrdersByCustomer(Long customerId);

    /**
     * Order nguồn VISIT của các lượt, theo lô: {@code visitId → orderId}, mọi trạng thái Order (Visit#6 để lại Order
     * CANCELLED). Lượt không có Order thì không có khóa trong map. Một câu SQL bất kể số id; tập rỗng → map rỗng, không
     * chạy SQL; bên gọi truyền tối đa 100 id và đã tự phân quyền. Đọc không khóa, chạy trong transaction của bên gọi.
     * Thêm 2026-10-20 bởi BE-1 cho hàng đợi visit (visit-v1 #2–4, docs/adr/0060).
     */
    Map<Long, Long> findOrderIdsByVisitIds(Collection<Long> visitIds);

    // ------------------------------------------------------------------------------------------------------------
    // Tầng 3 (T55c, docs/adr/0117, docs/06 §6b hàng 8, 13, 16): placeholder dạng default — ném UnsupportedOperationException
    // tới khi OrderQueryService ghi đè; khi đó xóa default (method thành trừu tượng).
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Một dòng Order trả được (BR-BH-13): chỉ dòng {@code GOODS}. {@code returnedQuantity} = đã trả (yêu cầu
     * {@code APPROVED} / {@code REFUNDED}), {@code pendingReturnQuantity} = đang chờ duyệt ({@code PENDING}).
     */
    record ReturnableLine(Long orderLineId, Long productId, String description, int quantity, long unitPrice,
                          int returnedQuantity, int pendingReturnQuantity) {}

    /**
     * Order cho trả hàng. {@code paidAt} và {@code collectMethod} null khi Order chưa {@code PAID}. {@code collectMethod}
     * là hình thức thu (BR-TG-12): quầy {@code CASH} / {@code TRANSFER} theo {@code payments.method}, đơn online
     * {@code GATEWAY}. Hạn trả của đơn online tính từ {@code delivered_at} (shop), không có ở đây.
     */
    record ReturnableOrder(Long orderId, Long branchId, Long customerId, OrderSource source, Instant paidAt,
                           CollectMethod collectMethod, List<ReturnableLine> lines) {}

    /** BR-BH-13, UC68: dòng trả được và số đã / đang trả của Order {@code orders.id}; không có Order thì rỗng. Đọc không khóa. Bản thật trước 30/11 (T73). */
    default Optional<ReturnableOrder> findLinesForReturn(Long orderId) {
        throw placeholder("findLinesForReturn", "T73");
    }

    /**
     * I-8 (07 §11, UC92): ngày mua gần nhất (ngày {@code paid_at} theo giờ Việt Nam) của từng sản phẩm trong các Order
     * {@code PAID} của khách; sản phẩm chưa mua thì vắng mặt. Bản thật ở AI-0 (9/11/2026).
     */
    Map<Long, LocalDate> findLastPurchaseDates(Long customerId, Collection<Long> productIds);

    /**
     * I-8 (07 §11, UC92): {@code productId → tổng số lượng bán} từ {@code since} (ngày {@code paid_at}, tính cả ngày đó)
     * trong các Order {@code PAID}, toàn chuỗi. Không trừ phần đã trả hàng (đặc tả im lặng, ghi ở ADR của AI-0).
     */
    Map<Long, Long> sumSoldUnitsByProduct(LocalDate since);

    /** BR-CN-07, BR-CN-08 (UC13): số Order {@code OPEN} / {@code PENDING} của chi nhánh. Bản thật trước 7/12 (T75). */
    default long countOpenByBranch(Long branchId) {
        throw placeholder("countOpenByBranch", "T75");
    }

    private static UnsupportedOperationException placeholder(String method, String task) {
        return new UnsupportedOperationException("OrderQueryApi." + method + " chưa cài (placeholder T55c, docs/06 §6b); "
                + "bản thật ở " + task);
    }
}
