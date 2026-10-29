package com.petcare.module.boarding.service;

/**
 * Nguyên nhân phòng khám hủy đặt chỗ lưu trú (Đặt chỗ#5, BR-LT-08, BR-LH-10). {@link #reason()} vừa là
 * {@code boarding_bookings.cancel_reason} vừa là biến {@code {ly_do}} của mẫu thông báo; câu giống
 * {@code appointment.service.ClinicCancelCause} cho cùng nguyên nhân.
 */
public enum BoardingClinicCause {

    /** BR-LH-10, BR-CN-03: thêm ngày nghỉ, quản lý chọn hủy hàng loạt. */
    HOLIDAY_ADDED("Chi nhánh nghỉ vào ngày nhận hoặc ngày trả của đặt chỗ"),
    /** BR-CN-04: thu hẹp giờ mở cửa, quản lý chọn hủy hàng loạt. */
    OPENING_HOURS_REDUCED("Chi nhánh thay đổi giờ mở cửa, ngày nhận hoặc ngày trả nằm ngoài giờ mở cửa mới"),
    /** BR-KH-05: thú được đánh dấu đã mất. */
    PET_DECEASED("Thú cưng đã được ghi nhận là đã mất"),
    /** BR-KH-08: thú được chuyển sang chủ mới. */
    PET_OWNER_TRANSFERRED("Thú cưng đã được chuyển sang chủ mới"),
    /** BR-LT-08: không còn chuồng trống đúng loại lúc nhận thú. */
    NO_KENNEL("Không còn chuồng trống đúng loại lúc nhận thú");

    private final String reason;

    BoardingClinicCause(String reason) {
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
