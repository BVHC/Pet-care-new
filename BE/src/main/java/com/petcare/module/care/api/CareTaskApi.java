package com.petcare.module.care.api;

import java.time.LocalDate;
import java.util.Collection;

/**
 * Owner: care (TB, Care Task 🆕) · BE-1. Đây là đầu vào của ST18.
 * Caller: visit (ST04 nhắc tái chủng / tái khám, ghi mũi tiêm), boarding (ST15 quá hạn đón).
 */
public interface CareTaskApi {

    /** Mã ASCII theo erd §0. */
    enum CareTaskType { VACCINE_DUE, VACCINE_OVERDUE, FOLLOW_UP_DUE, PICKUP_OVERDUE }

    /**
     * Care Task#1. {@code sourceId} = vaccinationId (VACCINE_*), visitId của bệnh án (FOLLOW_UP_DUE) hoặc
     * bookingId (PICKUP_OVERDUE). {@code branchId} là chi nhánh phụ trách do caller xác định (BR-TB-02).
     */
    record NewCareTask(CareTaskType type, Long sourceId, Long branchId, Long customerId, Long petId,
                       LocalDate dueDate) {}

    /** Idempotent với VACCINE_OVERDUE và PICKUP_OVERDUE (tối đa 1 task / nguồn): đã có thì trả về id cũ. */
    Long create(NewCareTask task);

    /** Care Task#5: hủy task VACCINE_DUE / VACCINE_OVERDUE OPEN của các mũi đã được tiêm lại (BR-TB-03). */
    void cancelForRevaccination(Collection<Long> supersededVaccinationIds);

    /** Care Task#6: hủy task FOLLOW_UP_DUE OPEN của các bệnh án có nhắc tái khám bị hủy (BR-TB-06). */
    void cancelFollowUpTasks(Collection<Long> medicalRecordVisitIds);
}
