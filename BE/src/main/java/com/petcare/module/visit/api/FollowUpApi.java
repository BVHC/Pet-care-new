package com.petcare.module.visit.api;

import java.time.LocalDate;

/** Owner: visit (KB, MedicalRecord.follow_up_*) · BE-1. Caller: appointment (LH). */
public interface FollowUpApi {

    /**
     * Lịch hẹn#1 nhóm MEDICAL → Care Task#6 (BR-TB-06): hủy nhắc tái khám còn chờ của thú lập trước hoặc
     * trong {@code bookedOn} (đặt {@code follow_up_cancelled_at}) và hủy Care Task FOLLOW_UP_DUE tương ứng.
     */
    void cancelPendingFollowUps(Long petId, LocalDate bookedOn);
}
