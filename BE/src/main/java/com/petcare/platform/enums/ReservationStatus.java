package com.petcare.platform.enums;

/**
 * docs/06-erd.md §3.5 inventory_reservations.status (VARCHAR thường, không Postgres native enum).
 * RULE-14-04 — HELD lúc CheckoutOrder giữ chỗ; RELEASED khi CancelOrder/ProcessOrderTimeout nhả
 * chỗ. COMMITTED (chuyển ReservedQuantity thành khấu trừ chính thức PhysicalQuantity lúc
 * ProcessOrder vào PROCESSING) chưa có call site trong phạm vi task Module 14 hiện tại — giữ đủ
 * 3 giá trị theo đúng ERD, không tự bịa thêm ngoài ERD.
 */
public enum ReservationStatus {
    HELD,
    COMMITTED,
    RELEASED
}
