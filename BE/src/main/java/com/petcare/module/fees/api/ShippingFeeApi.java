package com.petcare.module.fees.api;

/**
 * ST09, tính phí vận chuyển (BR-SP-08, docs/adr/0140). Chủ sở hữu: {@code fees} (BE-2). Bên gọi: {@code shop} (báo giá
 * và đặt đơn, tác vụ 117 / 125). Phí cố định toàn chuỗi (A1), không phụ thuộc địa chỉ giao. Bên gọi tự chốt
 * {@link ShippingFeeQuote#fee()} vào {@code online_orders.shipping_fee} lúc tạo đơn (BR-SP-11, BR-QT-13): đổi cấu hình
 * sau đó không ảnh hưởng đơn đã tạo.
 */
public interface ShippingFeeApi {

    /**
     * Phí cho đơn có tiền hàng {@code subtotal}: 0 khi {@code freeThreshold > 0} và {@code subtotal >= freeThreshold},
     * ngược lại {@code baseFee}. Đọc cấu hình trong transaction của bên gọi (nếu có).
     *
     * @throws IllegalArgumentException {@code subtotal < 0} (lỗi lập trình của bên gọi)
     */
    ShippingFeeQuote quote(long subtotal);
}
