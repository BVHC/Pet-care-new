package com.petcare.module.shop.api;

import java.time.Instant;
import java.util.Optional;

/**
 * Owner: shop (BH online) · BE-2. Caller: returns (UC68, ADR-0138 R3). Chỉ đọc, không khóa, chạy trong transaction của
 * bên gọi (docs/06 §6b, ADR-0145).
 */
public interface OnlineOrderQueryApi {

    /**
     * Thời điểm giao của đơn online ứng với Order {@code orders.id}: rỗng khi Order không phải đơn online hoặc đơn chưa
     * {@code DELIVERED} (hạn trả hàng tính từ đây, BR-BH-13).
     */
    Optional<Instant> findDeliveredAtByOrderId(Long orderId);
}
