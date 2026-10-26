package com.petcare.module.care.service;

import java.time.Clock;
import java.util.Collection;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.care.api.CareTaskApi;
import com.petcare.module.care.repository.CareTaskWriteRepository;

/**
 * {@link CareTaskApi} <b>một phần</b> (docs/adr/0074, nợ D047): chỉ {@link #cancelForRevaccination} (Care Task#5) có thật
 * vì ghi mũi tiêm (#19) cần; {@code create} và {@code cancelFollowUpTasks} ném {@link UnsupportedOperationException}
 * tới T39 (không trả "mặc định an toàn", CLAUDE.md *Unbuilt interface*). Mọi method {@code MANDATORY}.
 */
@Service
public class CareTaskService implements CareTaskApi {

    /** Lý do hủy do hệ thống đặt (03 Care Task#5 không quy định chữ); T39 xem lại (D047). */
    static final String REVACCINATED_REASON = "Đã tiêm lại (BR-TB-03)";

    private final CareTaskWriteRepository tasks;
    private final Clock clock;

    public CareTaskService(CareTaskWriteRepository tasks, Clock clock) {
        this.tasks = tasks;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Long create(NewCareTask task) {
        throw new UnsupportedOperationException("CareTaskApi.create chưa cài (T39, D047)");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelForRevaccination(Collection<Long> supersededVaccinationIds) {
        if (supersededVaccinationIds.isEmpty()) {
            return;
        }
        tasks.cancelOpenVaccineTasks(supersededVaccinationIds, REVACCINATED_REASON, clock.instant());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelFollowUpTasks(Collection<Long> medicalRecordVisitIds) {
        throw new UnsupportedOperationException("CareTaskApi.cancelFollowUpTasks chưa cài (T39, D047)");
    }
}
