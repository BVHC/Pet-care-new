package com.petcare.module.appointment.service;

import java.util.Objects;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.petcare.module.appointment.repository.AppointmentRepository;

/**
 * Khóa theo thú của lịch hẹn (docs/adr/0041): BR-LH-05 (≤ 2 lịch {@code BOOKED}, không trùng nhóm trong ngày, không trùng
 * khung) đếm trên mọi chi nhánh, nên khóa dòng {@code branches} không đủ. Thứ tự khóa của lịch hẹn:
 * {@code advisory(thú) → branches → appointments} — gọi method này <b>đầu tiên</b>, sau khi đã phân quyền.
 * <p>
 * Advisory theo transaction: tự nhả khi commit / rollback. Key = {@code pet_id % Integer.MAX_VALUE}; hai thú trùng key chỉ
 * làm lượt đặt của nhau chờ thừa, không sai nghiệp vụ. Lỗi khóa (deadlock) đánh dấu transaction rollback-only — bên gọi
 * không bắt. Bean riêng để lời gọi luôn đi qua proxy {@code MANDATORY}.
 */
@Component
public class AppointmentLocks {

    /** Namespace advisory lock của thú đặt lịch (bảng đăng ký namespace: docs/adr/0041). */
    public static final int PET_LOCK_NAMESPACE = 1001;

    /**
     * Namespace advisory lock của khách khi đánh giá hạn chế đặt online (BR-LH-09, docs/adr/0045). Luôn là khóa
     * <b>cuối cùng</b> của mọi luồng (hủy muộn, ST05, boarding): không luồng nào giữ khóa này rồi chờ khóa khác.
     */
    public static final int CUSTOMER_VIOLATION_NAMESPACE = 1002;

    private final AppointmentRepository appointments;

    public AppointmentLocks(AppointmentRepository appointments) {
        this.appointments = appointments;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockPet(Long petId) {
        lock(PET_LOCK_NAMESPACE, petId, "lockPet", "petId");
    }

    /**
     * Khóa theo khách trước khi đếm vi phạm và tạo hạn chế (docs/adr/0045): hai vi phạm đồng thời của cùng khách không
     * tạo hai dòng {@code booking_restrictions}, và vi phạm sau thấy vi phạm trước đã commit.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockCustomerViolations(Long customerId) {
        lock(CUSTOMER_VIOLATION_NAMESPACE, customerId, "lockCustomerViolations", "customerId");
    }

    private void lock(int namespace, Long id, String method, String name) {
        Objects.requireNonNull(id, name);
        if (id <= 0) {
            throw new IllegalArgumentException(name + " phải dương: " + id);
        }
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("AppointmentLocks." + method + " cần transaction ghi, không được readOnly");
        }
        appointments.acquireTransactionLock(namespace, key(id));
    }

    static int petKey(long petId) {
        return key(petId);
    }

    static int key(long id) {
        return (int) (id % Integer.MAX_VALUE);
    }
}
