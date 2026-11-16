package com.petcare.module.payment.api;

/**
 * Owner: payment (TG online) · BE-2. Caller: shop (task 125 khách hủy, task 126 ST10 quá hạn). Giao dịch#5 (08 §B
 * FSM #15): giao dịch {@code PENDING} của một đơn online chuyển {@code CANCELLED} khi đơn bị hủy. Cổng VNPay không hủy
 * được phiên chưa thanh toán (08 §C), nên chỉ đổi trạng thái giao dịch nội bộ. Cả hai method {@code MANDATORY}; bên gọi
 * đã khóa {@code online_orders} và Order của đơn (thứ tự khóa ADR-0145).
 */
public interface PaymentTransactionApi {

    /**
     * Giao dịch#5: một câu UPDATE {@code PENDING} → {@code CANCELLED} cho mọi giao dịch {@code PENDING} của
     * {@code onlineOrderId} ({@code online_orders.id}); trả số giao dịch đã hủy. Idempotent: không còn giao dịch
     * {@code PENDING} thì trả 0 và không ghi gì.
     */
    int cancelPendingForOnlineOrder(Long onlineOrderId);

    /** Đơn đã có giao dịch {@code SUCCEEDED}: khách không tự hủy được ở {@code PENDING_PAYMENT} (Đơn hàng online#3). Chỉ đọc. */
    boolean hasSucceededForOnlineOrder(Long onlineOrderId);
}
