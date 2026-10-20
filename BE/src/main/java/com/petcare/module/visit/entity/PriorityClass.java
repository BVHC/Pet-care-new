package com.petcare.module.visit.entity;

/**
 * Hạng ưu tiên trong hàng đợi ({@code visits.priority_class}, erd §6; BR-TN-03, BR-TN-04): ca cấp cứu → lịch hẹn →
 * walk-in. Khách trễ 15–30 phút được xếp {@code WALK_IN} dù có lịch hẹn.
 */
public enum PriorityClass {
    EMERGENCY,
    APPOINTMENT,
    WALK_IN
}
