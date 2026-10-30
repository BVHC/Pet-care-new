package com.petcare.module.sales.api;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * Owner: sales (BH) · BE-2. Caller: boarding (LT) · BE-2. Order nguồn BOARDING. Mọi method {@code MANDATORY}: chạy
 * trong transaction của boarding, sau khi boarding đã khóa đặt chỗ (docs/adr/0086).
 */
public interface BoardingOrderApi {

    /** {@code nightlyPrice} là giá đã snapshot lúc đặt chỗ (BR-LT-02), không lấy giá hiện tại. */
    record BoardingCharge(Long bookingId, Long branchId, Long customerId, Long kennelTypeServiceId,
                          String description, int nights, long nightlyPrice, Long actorId) {}

    /** Order lưu trú vừa mở / dùng lại / đã thu: số đêm tính tiền và tổng tiền (VND). */
    record BoardingOrderRef(Long orderId, int nights, long amount) {}

    /**
     * Đặt chỗ#9, #11 → Order#3: tạo Order PENDING; nếu đặt chỗ đã có Order BOARDING PENDING thì dùng lại và
     * tính lại số đêm (BR-LT-09, 12). Đặt chỗ đã có Order PAID thì {@code InvalidStateTransitionException} (409): không
     * sinh Order thứ hai.
     */
    BoardingOrderRef openOrReuse(BoardingCharge charge);

    /** Điều kiện Đặt chỗ#10: Order lưu trú của đặt chỗ đã PAID (BR-LT-09, BR-BH-06). */
    boolean hasPaidOrder(Long bookingId);

    /** Order lưu trú đã PAID mới nhất của đặt chỗ (thú mất sau khi khách đã thu tiền, BR-LT-12). */
    Optional<BoardingOrderRef> findPaidOrder(Long bookingId);

    /** bookingId → orderId của Order OPEN / PENDING; đặt chỗ không có thì vắng mặt. Tập rỗng không chạm DB. */
    Map<Long, Long> findActiveOrderIds(Collection<Long> bookingIds);
}
