package com.petcare.module.returns.api;

/**
 * Owner: returns (BH trả hàng) · BE-2. Caller: shop (ST12 tự xác nhận đã nhận, task 126). Chỉ đọc, không khóa, chạy trong
 * transaction của bên gọi ({@code MANDATORY}); bên gọi đã khóa {@code online_orders} (thứ tự khóa ADR-0145).
 */
public interface ReturnRequestQueryApi {

    /**
     * Order {@code orders.id} (với đơn online là {@code online_orders.order_id}, không phải {@code online_orders.id}) có
     * yêu cầu trả hàng đang mở: trạng thái {@code PENDING} hoặc {@code APPROVED}, tức chưa về trạng thái cuối
     * ({@code REJECTED}, {@code CANCELLED}, {@code REFUNDED}; 08 §D FSM #16). Không có yêu cầu nào thì {@code false}.
     */
    boolean hasOpenRequestForOrder(Long orderId);
}
