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

    private final AppointmentRepository appointments;

    public AppointmentLocks(AppointmentRepository appointments) {
        this.appointments = appointments;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockPet(Long petId) {
        Objects.requireNonNull(petId, "petId");
        if (petId <= 0) {
            throw new IllegalArgumentException("petId phải dương: " + petId);
        }
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("AppointmentLocks.lockPet cần transaction ghi, không được readOnly");
        }
        appointments.acquireTransactionLock(PET_LOCK_NAMESPACE, petKey(petId));
    }

    static int petKey(long petId) {
        return (int) (petId % Integer.MAX_VALUE);
    }
}
