package com.petcare.module.care.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.care.api.CareTaskApi;
import com.petcare.module.care.entity.CareTask;
import com.petcare.module.care.entity.CareTaskStatus;
import com.petcare.module.care.fsm.CareTaskTransitionHandler;
import com.petcare.module.care.repository.CareTaskRepository;

/**
 * {@link CareTaskApi} (docs/adr/0079, trả D047). Mọi method {@code MANDATORY}: chạy trong transaction của bên gọi (visit
 * ghi mũi tiêm, ST04 / ST18, boarding ST15, {@code FollowUpApi}), không {@code catch}, không audit, không outbox. Care
 * không đọc bảng của module khác nên không kiểm thú, chủ, email, SĐT hay chi nhánh phụ trách: đó là nghĩa vụ bên gọi
 * (javadoc {@link CareTaskApi#create}).
 */
@Service
public class CareTaskService implements CareTaskApi {

    /** Lý do hủy do hệ thống đặt (03 Care Task#5 không quy định chữ). Giữ nguyên chữ của ADR-0074. */
    static final String REVACCINATED_REASON = "Đã tiêm lại (BR-TB-03)";

    /** Care Task#6. */
    static final String FOLLOW_UP_CANCELLED_REASON = "Đã có lịch khám / lượt khám mới (BR-TB-06)";

    private final CareTaskRepository tasks;
    private final CareTaskTransitionHandler fsm;
    private final Clock clock;

    public CareTaskService(CareTaskRepository tasks, CareTaskTransitionHandler fsm, Clock clock) {
        this.tasks = tasks;
        this.fsm = fsm;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Long create(NewCareTask task) {
        CareTask entity = CareTask.open(task);
        fsm.validateInitial(CareTaskStatus.OPEN);
        return switch (entity.getTaskType()) {
            case VACCINE_DUE, FOLLOW_UP_DUE -> tasks.saveAndFlush(entity).getId();
            case VACCINE_OVERDUE -> {
                tasks.insertVaccineOverdueIfAbsent(entity.getBranchId(), entity.getCustomerId(), entity.getPetId(),
                        entity.getVaccinationId(), entity.getDueDate(), clock.instant());
                yield tasks.findVaccineOverdueId(entity.getVaccinationId())
                        .orElseThrow(() -> missingAfterInsert(entity));
            }
            case PICKUP_OVERDUE -> {
                tasks.insertPickupOverdueIfAbsent(entity.getBranchId(), entity.getCustomerId(), entity.getPetId(),
                        entity.getBoardingBookingId(), entity.getDueDate(), clock.instant());
                yield tasks.findPickupOverdueId(entity.getBoardingBookingId())
                        .orElseThrow(() -> missingAfterInsert(entity));
            }
        };
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelForRevaccination(Collection<Long> supersededVaccinationIds) {
        if (supersededVaccinationIds.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        tasks.cancelOpenVaccineTasks(supersededVaccinationIds, REVACCINATED_REASON, now);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelFollowUpTasks(Collection<Long> medicalRecordVisitIds) {
        if (medicalRecordVisitIds.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        tasks.cancelOpenFollowUpTasks(medicalRecordVisitIds, FOLLOW_UP_CANCELLED_REASON, now);
    }

    /**
     * {@code ON CONFLICT DO NOTHING} không chèn mà SELECT cũng không thấy: chỉ xảy ra khi bên gọi chạy REPEATABLE READ
     * (không thấy dòng do transaction khác vừa commit) — vi phạm nghĩa vụ ở {@link CareTaskApi#create}.
     */
    private static IllegalStateException missingAfterInsert(CareTask task) {
        return new IllegalStateException("Care Task " + task.getTaskType() + " không chèn được mà cũng không thấy dòng "
                + "đã có (bên gọi phải chạy READ COMMITTED)");
    }
}
