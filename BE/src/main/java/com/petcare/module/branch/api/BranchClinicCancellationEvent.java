package com.petcare.module.branch.api;

import java.util.List;

/**
 * Publisher: branch (UC14) · BE-2, khi BRANCH_MANAGER chọn hủy hàng loạt lúc thêm ngày nghỉ hoặc thu hẹp giờ
 * mở cửa (BR-CN-03, 04, BR-LH-10). Danh sách id do branch tính trước từ {@code AppointmentQueryApi.findBookedFrom}
 * và {@code BoardingQueryApi.findBookedFrom}.
 * Listener: appointment (Lịch hẹn#5), boarding (Đặt chỗ#5); mỗi listener tự thông báo khách.
 */
public record BranchClinicCancellationEvent(Long branchId, Cause cause, List<Long> appointmentIds,
                                            List<Long> boardingBookingIds, Long actorId) {

    public enum Cause { HOLIDAY_ADDED, OPENING_HOURS_REDUCED }
}
