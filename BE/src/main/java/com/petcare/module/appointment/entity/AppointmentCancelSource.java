package com.petcare.module.appointment.entity;

/**
 * Ai hủy lịch hẹn ({@code appointments.cancel_source}, erd §5): {@code CUSTOMER} khách tự hủy, {@code STAFF} lễ tân hủy
 * hộ (BR-LH-07), {@code CLINIC} phòng khám hủy (Lịch hẹn#5: ngày nghỉ / thu hẹp giờ, thú mất, chuyển chủ — BR-LH-10).
 * Chỉ có giá trị khi {@code CANCELLED} (CHECK {@code ck_appointments_cancel_fields}).
 */
public enum AppointmentCancelSource {
    CUSTOMER,
    STAFF,
    CLINIC
}
