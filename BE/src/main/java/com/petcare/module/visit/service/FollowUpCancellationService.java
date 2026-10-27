package com.petcare.module.visit.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.care.api.CareTaskApi;
import com.petcare.module.visit.api.FollowUpApi;
import com.petcare.module.visit.repository.MedicalRecordRepository;

/**
 * {@link FollowUpApi} (Care Task#6, BR-TB-06; docs/adr/0084, trả D029) và hủy nhắc tái khám khi thú mất (BR-TB-03, ST19).
 * Mọi method {@code MANDATORY}: chạy trong transaction của bên gọi, không {@code catch}, không audit, không outbox.
 * <p>
 * Thứ tự trong {@link #cancelPendingFollowUps} là bắt buộc, READ COMMITTED: (1) hủy nhắc chưa gửi bằng một UPDATE (dòng
 * đang bị ST04 giữ thì chờ, rồi kiểm lại điều kiện và bỏ qua dòng ST04 vừa đánh dấu đã nhắc) → (2) đọc các bệnh án
 * <b>đã nhắc</b> → (3) hủy task {@code FOLLOW_UP_DUE} của chúng. Bước 2 và 3 thấy cả task ST04 vừa commit; ngược lại nếu
 * bước 1 khóa dòng trước thì ST04 gặp {@code SKIP LOCKED} và bỏ qua. Khóa {@code medical_records} luôn trước
 * {@code care_tasks}.
 */
@Service
public class FollowUpCancellationService implements FollowUpApi {

    private final MedicalRecordRepository records;
    private final CareTaskApi careTasks;
    private final Clock clock;

    public FollowUpCancellationService(MedicalRecordRepository records, CareTaskApi careTasks, Clock clock) {
        this.records = records;
        this.careTasks = careTasks;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelPendingFollowUps(long petId, Instant at) {
        records.cancelPendingFollowUpsOfPet(petId, at);
        List<Long> reminded = records.findRemindedFollowUpIdsOfPet(petId);
        careTasks.cancelFollowUpTasks(reminded);
    }

    /**
     * Thú đã mất: chỉ hủy nhắc chưa gửi. Task đã sinh (mọi loại) do care tự hủy trong {@code CarePetDeceasedListener},
     * chạy sau listener của visit để giữ thứ tự khóa {@code medical_records → care_tasks}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelForDeceasedPet(long petId) {
        records.cancelPendingFollowUpsOfPet(petId, clock.instant().truncatedTo(ChronoUnit.MICROS));
    }
}
