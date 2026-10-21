package com.petcare.module.visit.entity;

/**
 * Hạng ưu tiên trong hàng đợi ({@code visits.priority_class}, erd §6; BR-TN-03, BR-TN-04): ca cấp cứu → lịch hẹn →
 * walk-in. Khách trễ 15–30 phút được xếp {@code WALK_IN} dù có lịch hẹn. Đây là hạng <b>trong hàng đợi</b>, không phải
 * "có lịch hẹn hay không" (xem {@code appointment_id}): lễ tân sắp lại thì lượt nhận hạng của lượt đứng sau nó, và
 * xác định ca cấp cứu đổi sang {@code EMERGENCY} (A2, A3; docs/adr/0061).
 */
public enum PriorityClass {
    EMERGENCY,
    APPOINTMENT,
    WALK_IN
}
