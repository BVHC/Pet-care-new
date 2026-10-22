package com.petcare.module.appointment.entity;

/**
 * Trạng thái lịch hẹn ({@code appointments.status}, 03 §3, BR-LH-11). {@code COMPLETED}, {@code CANCELLED},
 * {@code NO_SHOW} là trạng thái cuối. Chuyển trạng thái theo {@code AppointmentTransitionHandler}.
 */
public enum AppointmentStatus {
    BOOKED,
    CHECKED_IN,
    COMPLETED,
    CANCELLED,
    NO_SHOW
}
