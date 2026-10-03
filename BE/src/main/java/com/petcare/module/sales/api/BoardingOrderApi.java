package com.petcare.module.sales.api;

/** Owner: sales (BH) · BE-2. Caller: boarding (LT) · BE-2. Order nguồn BOARDING. */
public interface BoardingOrderApi {

    /** {@code nightlyPrice} là giá đã snapshot lúc đặt chỗ (BR-LT-02), không lấy giá hiện tại. */
    record BoardingCharge(Long bookingId, Long branchId, Long customerId, Long kennelTypeServiceId,
                          String description, int nights, long nightlyPrice, Long actorId) {}

    /**
     * Đặt chỗ#9, #11 → Order#3: tạo Order PENDING; nếu đặt chỗ đã có Order BOARDING PENDING thì dùng lại và
     * tính lại số đêm (BR-LT-09, 12). Trả về orderId.
     */
    Long openOrReuse(BoardingCharge charge);

    /** Điều kiện Đặt chỗ#10: Order lưu trú của đặt chỗ đã PAID (BR-LT-09, BR-BH-06). */
    boolean hasPaidOrder(Long bookingId);
}
