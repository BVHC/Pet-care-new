package com.petcare.module.appointment.api;

import java.time.Instant;

/** Owner: appointment (LH, BookingRestriction) · BE-1. Caller: boarding (LT). */
public interface BookingRestrictionApi {

    /** Khách đang bị hạn chế đặt online (BR-LH-09), áp dụng cho cả đặt lịch và đặt chỗ lưu trú. */
    boolean isRestricted(Long customerId, Instant at);

    /**
     * Gọi ngay sau mỗi vi phạm (NO_SHOW hoặc hủy muộn) của lịch hẹn hoặc đặt chỗ, trong cùng transaction.
     * Đếm vi phạm 90 ngày [CFG] từ cả hai nguồn; đủ 3 [CFG] thì tạo BookingRestriction 30 ngày [CFG].
     */
    void evaluateAfterViolation(Long customerId, Instant at);
}
