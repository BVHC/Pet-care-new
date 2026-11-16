package com.petcare.module.sales.api;

import java.util.List;

/**
 * Owner: sales (BH) · BE-2. Caller: shop (task 117) · BE-2. Tạo Order nguồn ONLINE (Order#11, BR-BH-09). Mã Order do
 * shop sinh trước (một lần, cùng {@code DocumentCodeGenerator} "ORDER" / "DH") để giữ thứ tự khóa của ADR-0144: mã
 * đã có trước các INSERT. {@code MANDATORY}: chạy trong transaction đặt hàng của shop; shop đã khóa giỏ, sản phẩm và
 * các lô kho. Quầy không thu và không hủy Order ONLINE (thu qua cổng, khách hủy hoặc ST10).
 */
public interface OnlineSalesOrderApi {

    /** Dòng hàng đã chốt: {@code unitPrice} là bản chụp giá lúc đặt (BR-BH-03), không đọc lại giá hiện tại. */
    record OnlineOrderLineInput(Long productId, String name, int quantity, long unitPrice) {}

    /**
     * Tạo Order {@code PENDING} (pending_at = giờ hiện tại) với các dòng GOODS, tổng tính lại từ dòng; trả id Order.
     * Danh sách rỗng, số lượng ≤ 0 hoặc giá âm là lỗi lập trình ({@code IllegalArgumentException}); tổng tràn
     * {@code long} thì 400 như mọi Order.
     */
    Long createPendingOrder(String code, Long branchId, Long customerId, Long accountId,
                            List<OnlineOrderLineInput> lines);
}
