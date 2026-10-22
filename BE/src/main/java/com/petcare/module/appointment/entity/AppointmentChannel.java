package com.petcare.module.appointment.entity;

/**
 * Kênh đặt lịch ({@code appointments.channel}, erd §5): {@code ONLINE} khách tự đặt, {@code COUNTER} lễ tân đặt hộ
 * (appointment-v1 A2). Suy ra từ role của người đặt, không nhận từ request (docs/adr/0045).
 */
public enum AppointmentChannel {
    ONLINE,
    COUNTER
}
