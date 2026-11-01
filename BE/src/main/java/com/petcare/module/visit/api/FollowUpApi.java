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

    /**
     * Lịch hẹn#1 nhóm MEDICAL → Care Task#6 (BR-TB-06, docs/adr/0088, thay docs/adr/0084 §2, §5): chỉ hủy Care Task
     * FOLLOW_UP_DUE {@code OPEN} của các bệnh án mà ST04 <b>đã nhắc</b>. Không đặt {@code follow_up_cancelled_at}: nhắc
     * chưa gửi do ST04 tự hoãn khi thú đang có lịch Khám/Tiêm {@code BOOKED} (và gửi lại khi lịch rời {@code BOOKED}).
     * Trước khi đọc, khóa các bệnh án còn chờ nhắc của thú ({@code FOR NO KEY UPDATE}, không ghi) để chờ ST04 đang giữ
     * dòng commit xong, rồi mới thấy task nó vừa sinh. Cùng nghĩa vụ bên gọi với {@link #cancelPendingFollowUps}.
     */
    void cancelRemindedFollowUpTasks(long petId);
}
