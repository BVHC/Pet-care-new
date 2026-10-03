package com.petcare.module.appointment.api;

import java.time.LocalDateTime;

/** Owner: appointment (LH) · BE-1. Caller: visit (TN). Gọi trong transaction của Visit. */
public interface AppointmentApi {

    record CheckInInfo(Long appointmentId, Long customerId, Long petId, Long serviceId,
                       LocalDateTime scheduledAt) {}

    /**
     * Visit#1 → Lịch hẹn#3. Kiểm tra lịch BOOKED, đúng chi nhánh, đúng ngày, không sớm hơn 30 phút [CFG]
     * (BR-TN-02). Visit tự xếp ưu tiên theo {@code scheduledAt} (BR-TN-03).
     */
    CheckInInfo checkIn(Long appointmentId, Long branchId, LocalDateTime at, Long actorId);

    /** Visit#5 → Lịch hẹn#7. */
    void completeByVisit(Long appointmentId);

    /** Visit#6 → Lịch hẹn#8, {@code late_cancel = false} (BR-TN-07). */
    void cancelByVisit(Long appointmentId, Long actorId);
}
