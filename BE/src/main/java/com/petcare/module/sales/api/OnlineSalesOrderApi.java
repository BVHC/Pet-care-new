package com.petcare.module.sales.api;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Owner: sales (BH) · BE-2. Caller: shop (task 117) · BE-2. Tạo Order nguồn ONLINE (Order#11, BR-BH-09). Mã Order do
 * shop sinh trước (một lần, cùng {@code DocumentCodeGenerator} "ORDER" / "DH") để giữ thứ tự khóa của ADR-0144: mã
 * đã có trước các INSERT. {@code MANDATORY}: chạy trong transaction đặt hàng của shop; shop đã khóa giỏ, sản phẩm và
 * các lô kho. Quầy không thu và không hủy Order ONLINE (thu qua cổng, khách hủy hoặc ST10).
 */
public interface OnlineSalesOrderApi {

    /** Dãy mã chứng từ của Order bán hàng (dùng chung với Order tại quầy, ADR-0043): {@code DH-yyMM-00001}. */
    String ORDER_DOC_TYPE = "ORDER";
    String ORDER_PREFIX = "DH";

    /** Dòng hàng đã chốt: {@code unitPrice} là bản chụp giá lúc đặt (BR-BH-03), không đọc lại giá hiện tại. */
    record OnlineOrderLineInput(Long productId, String name, int quantity, long unitPrice) {}

    /**
     * Tạo Order {@code PENDING} (pending_at = giờ hiện tại) với các dòng GOODS, tổng tính lại từ dòng; trả id Order.
     * Danh sách rỗng, số lượng ≤ 0 hoặc giá âm là lỗi lập trình ({@code IllegalArgumentException}); tổng tràn
     * {@code long} thì 400 như mọi Order.
     */
    Long createPendingOrder(String code, Long branchId, Long customerId, Long accountId,
                            List<OnlineOrderLineInput> lines);

    /** Ai hủy Order ONLINE: khách ({@code ONLINE_CUSTOMER}, Đơn hàng online#3) hay hệ thống quá hạn ({@code ONLINE_EXPIRED}, #4). */
    enum OnlineCancelType { CUSTOMER, EXPIRED }

    /**
     * Order#13 (Đơn hàng online#3, #4): {@code PENDING} → {@code CANCELLED}, {@code cancel_type} theo {@code type},
     * {@code cancelled_by = actorId} ({@code null} = hệ thống), {@code cancel_reason = reason}. Khóa dòng Order trước khi
     * đọc (ADR-0145: sau khóa {@code online_orders}). Order không phải nguồn ONLINE hoặc không tồn tại là lỗi của bên gọi
     * ({@code IllegalStateException}); Order không còn {@code PENDING} là 409 {@code INVALID_STATE_TRANSITION}.
     * {@code MANDATORY}. Hủy sau khi {@code PAID} không đổi Order (08 §B #14b), nên không có ở đây.
     */
    void cancel(Long orderId, OnlineCancelType type, Long actorId, String reason);

    /**
     * Order#12 (Đơn hàng online#2, task 127): {@code PENDING} → {@code PAID}, {@code paid_at = paidAt}; {@code payment_id}
     * luôn NULL với Order ONLINE (V42: tiền nằm ở {@code payment_transactions}). Khóa dòng Order trước khi đọc (ADR-0145
     * C3: sau khóa {@code online_orders}, trước {@code payment_transactions}). Idempotent: Order đã {@code PAID} thì không
     * đổi gì. Order không tồn tại hoặc không phải nguồn ONLINE là lỗi của bên gọi ({@code IllegalStateException}); Order
     * {@code CANCELLED} là 409 {@code INVALID_STATE_TRANSITION}. {@code MANDATORY}. Kho xuất riêng qua
     * {@code StockApi.consumeReservation}.
     */
    void markPaid(Long orderId, Instant paidAt);

    /**
     * Dòng hàng đã chốt của các Order ONLINE, theo lô: {@code orderId → dòng} (giá là bản chụp lúc đặt). Order không có
     * dòng vắng mặt trong map; tập rỗng → map rỗng, không chạy SQL. Chỉ đọc, không khóa.
     */
    Map<Long, List<OnlineOrderLineInput>> findLines(Collection<Long> orderIds);
}
