package com.petcare.module.appointment.service;

/**
 * Nguyên nhân của Lịch hẹn#5 ({@code cancelByClinic}, docs/adr/0047 Q6). {@link #reason()} vừa ghi vào
 * {@code appointments.cancel_reason} (≤ 300, khách đọc trong {@code AppointmentResponse.cancelReason}) vừa là biến
 * {@code ly_do} của mẫu {@code APPOINTMENT_CANCELLED_BY_CLINIC}: một nguồn cho hai chỗ.
 */
public enum ClinicCancelCause {

    /** BR-LH-10, BR-CN-03: thêm ngày nghỉ, quản lý chọn hủy hàng loạt. */
    HOLIDAY_ADDED("Chi nhánh nghỉ vào ngày hẹn"),
    /** BR-CN-04: thu hẹp giờ mở cửa, quản lý chọn hủy hàng loạt. */
    OPENING_HOURS_REDUCED("Chi nhánh thay đổi giờ mở cửa, giờ hẹn nằm ngoài giờ mở cửa mới"),
    /** BR-KH-05: thú được đánh dấu đã mất. */
    PET_DECEASED("Thú cưng đã được ghi nhận là đã mất"),
    /** BR-KH-08: thú được chuyển sang chủ mới. */
    PET_OWNER_TRANSFERRED("Thú cưng đã được chuyển sang chủ mới");

    private final String reason;

    ClinicCancelCause(String reason) {
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
