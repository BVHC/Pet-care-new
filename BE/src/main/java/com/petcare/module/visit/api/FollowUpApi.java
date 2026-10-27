package com.petcare.module.visit.api;

import java.time.Instant;

/** Owner: visit (KB, MedicalRecord.follow_up_*) · BE-1. Caller: appointment (LH), visit (check-in). */
public interface FollowUpApi {

    /**
     * Lịch hẹn#1 nhóm MEDICAL / Visit#1 có dịch vụ Khám → Care Task#6 (BR-TB-06, docs/adr/0084): hủy nhắc tái khám còn
     * chờ của thú (đặt {@code follow_up_cancelled_at = at}) và hủy Care Task FOLLOW_UP_DUE {@code OPEN} tương ứng. Chỉ
     * xét bệnh án đã khóa (Visit {@code COMPLETED}): ngày tái khám của lượt đang khám còn sửa được. {@code at} là mốc của
     * use case bên gọi, lấy sau các khóa.
     *
     * <p><b>Nghĩa vụ bên gọi:</b> trong transaction ghi của mình (method {@code MANDATORY}), <b>READ COMMITTED</b>, không
     * {@code catch}; gọi <b>sau</b> mọi khóa {@code advisory} / {@code branches} / {@code pets} / {@code appointments} và
     * trước khi chạm {@code care_tasks}: mọi luồng chạm cả hai bảng khóa {@code medical_records} trước {@code care_tasks}.
     */
    void cancelPendingFollowUps(long petId, Instant at);
}
