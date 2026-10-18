package com.petcare.module.appointment.api;

import java.time.Instant;

import com.petcare.module.catalog.api.ServiceGroup;

/**
 * Owner: appointment (LH) · BE-1. Caller: visit (TN). Cài đặt: {@code AppointmentVisitService} (docs/adr/0046).
 * <p>
 * <b>Nghĩa vụ của bên gọi</b> (visit, T26), áp dụng cho cả ba method:
 * <ul>
 * <li>Gọi <b>trong transaction ghi</b> của Visit#1 / #5 / #6; implementation là {@code MANDATORY}, từ chối ngoài
 * transaction và trong transaction {@code readOnly}. Commit / rollback do bên gọi quyết định.</li>
 * <li><b>Không bắt lỗi</b> của các method này (kể cả {@code InvalidStateTransitionException},
 * {@code BusinessRuleViolationException}): lỗi đã đánh dấu transaction rollback-only, bắt rồi đi tiếp thì commit ném
 * {@code UnexpectedRollbackException} (docs/adr/0010).</li>
 * <li>Gọi <b>sau</b> mọi khóa {@code branches} / advisory của visit, không bao giờ khóa dòng {@code appointments} trước
 * rồi khóa {@code branches} (deadlock với đổi khung: {@code advisory(thú) → branches → appointments}, docs/adr/0041).</li>
 * <li>{@code at} là một mốc duy nhất của use case, lấy <b>sau</b> các khóa của visit; có thể trễ bằng thời gian chờ khóa
 * dòng lịch hẹn (chấp nhận).</li>
 * </ul>
 * Appointment không kiểm lại guard của visit: role A06 và {@code BranchScope}, chi nhánh {@code ACTIVE} trong giờ mở
 * cửa / cờ cấp cứu (BR-CN-05), thú chưa mất (BR-TN-01), xếp ưu tiên trễ 15–30 phút (BR-TN-03, dùng
 * {@code scheduledAt}), một Visit mỗi lịch ({@code uq_visits_appointment_id}), chẩn đoán / mũi tiêm (BR-KB-02), lượt
 * chưa gọi (BR-TN-07).
 */
public interface AppointmentApi {

    record CheckInInfo(Long appointmentId, Long customerId, Long petId, Long serviceId, ServiceGroup serviceGroup,
                       Instant scheduledAt) {}

    /**
     * Visit#1 → Lịch hẹn#3. Thứ tự lỗi: 404 không có lịch → 409 không còn {@code BOOKED} → 400 BR-TN-02 (khác chi
     * nhánh, khác ngày, sớm hơn {@code visit.check_in_early_minutes} [CFG], hoặc trễ hơn
     * {@code appointment.no_show_after_minutes} [CFG] — biên tính là được). 409 đứng trước 400 là lệch có chủ đích so
     * với convention 05 (docs/adr/0046, như 0045). {@code scheduledAt} là giờ hẹn.
     */
    CheckInInfo checkIn(Long appointmentId, Long branchId, Instant at);

    /** Visit#5 → Lịch hẹn#7 ({@code CHECKED_IN → COMPLETED}). Lịch không có = dữ liệu hỏng (500). */
    void completeByVisit(Long appointmentId);

    /**
     * Visit#6 → Lịch hẹn#8 ({@code CHECKED_IN → CANCELLED}): {@code cancel_source = STAFF}, {@code late_cancel = false}
     * (BR-TN-07), {@code cancelled_at = at} cắt về micro giây. Không tính vi phạm BR-LH-09.
     */
    void cancelByVisit(Long appointmentId, Instant at);
}
