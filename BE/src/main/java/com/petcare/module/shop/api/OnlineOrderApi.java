package com.petcare.module.shop.api;

/**
 * Owner: shop (BH online) · BE-2. Caller: payment (UC63). Mới ở T55c (docs/adr/0117, docs/06 §6b hàng 4): trước khi có
 * bản thật, {@code OnlineOrderApiPlaceholder} ném {@link UnsupportedOperationException}. {@code onlineOrderId} là
 * {@code online_orders.id} (không phải {@code orders.id}).
 */
public interface OnlineOrderApi {

    /**
     * Giao dịch#2 → Đơn hàng online#2 (08 §B, §C): cổng báo thành công, số tiền khớp. Chuyển đơn {@code PENDING_PAYMENT} →
     * {@code PAID}, xuất kho FEFO từ phần giữ và Order {@code PAID} trong cùng transaction. {@code transactionCode} là mã
     * tham chiếu giao dịch của hệ thống ({@code payment_transactions.code}); gọi lại cùng mã không đổi gì (BR-TG-07).
     * {@code MANDATORY}. Bản thật trước 24/11 (T65).
     */
    void markPaid(Long onlineOrderId, String transactionCode);

    /**
     * 08 §C (hợp đồng chéo) chỉ nêu tên và tham số của method này; ngữ nghĩa chốt ở T65 cùng Q3 của 08 §C (cổng có hủy
     * được phiên chưa thanh toán không) và ghi vào ADR của T65 (ADR-0117, TBD-5). {@code MANDATORY}. Bản thật trước
     * 24/11 (T65).
     */
    void markPaymentCancelled(Long onlineOrderId);
}
