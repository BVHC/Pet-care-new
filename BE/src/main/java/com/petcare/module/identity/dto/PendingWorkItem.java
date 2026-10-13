package com.petcare.module.identity.dto;

/**
 * Một quy trình dở dang của tài khoản vừa bị khóa (identity-v1 {@code PendingWorkItem}; BR-QT-11, BR-TN-08, 06 §8 Q2 —
 * docs/adr/0035). {@code entityId} là id nội bộ của lượt / ca / lịch hẹn / đặt chỗ / Order; {@code branchId} là chi
 * nhánh đang giữ việc đó. {@code needsReassign} chỉ {@code true} với lượt đã gọi ({@code VISIT_IN_PROGRESS}).
 */
public record PendingWorkItem(PendingWorkType type, Long entityId, Long branchId, boolean needsReassign) {

    /**
     * Đúng 6 giá trị và đúng thứ tự của contract. Thứ tự khai báo cũng là thứ tự sắp trong response (lượt cần gán lại
     * lên đầu).
     */
    public enum PendingWorkType {
        VISIT_IN_PROGRESS, VISIT_WAITING, CASHIER_SHIFT_OPEN, APPOINTMENT_BOOKED, BOARDING_ACTIVE, ORDER_UNPAID
    }
}
