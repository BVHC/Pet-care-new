package com.petcare.module.appointment.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.petcare.module.appointment.api.AppointmentApi;
import com.petcare.module.appointment.entity.Appointment;
import com.petcare.module.appointment.entity.AppointmentCancelSource;
import com.petcare.module.appointment.entity.AppointmentStatus;
import com.petcare.module.appointment.fsm.AppointmentTransitionHandler;
import com.petcare.module.appointment.repository.AppointmentRepository;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;

/**
 * Hệ quả của Visit lên lịch hẹn: Lịch hẹn#3 ({@code checkIn}), #7 ({@code completeByVisit}), #8
 * ({@code cancelByVisit}) — docs/adr/0046. Không mở transaction riêng: {@code MANDATORY}, tham gia transaction của
 * Visit#1 / #5 / #6, không {@code noRollbackFor}, không {@code try/catch}; mọi kiểm tra đứng <b>trước</b>
 * {@code saveAndFlush}, nên từ chối thì không ghi gì và lỗi DB lộ ngay trong lời gọi.
 * <p>
 * Chỉ khóa dòng {@code appointments} ({@code FOR NO KEY UPDATE}) — đoạn cuối của thứ tự
 * {@code advisory(thú) → branches → appointments} — và mọi guard đọc trạng thái <b>sau</b> khóa. Không đọc clock (dùng
 * {@code at} của visit), không audit, không thông báo, không gọi module khác.
 */
@Service
public class AppointmentVisitService implements AppointmentApi {

    private static final String FSM_NAME = AppointmentChangeService.FSM_NAME;

    private final AppointmentRepository appointments;
    private final AppointmentTransitionHandler transitions;
    private final SystemConfigApi configs;

    public AppointmentVisitService(AppointmentRepository appointments, AppointmentTransitionHandler transitions,
            SystemConfigApi configs) {
        this.appointments = appointments;
        this.transitions = transitions;
        this.configs = configs;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public CheckInInfo checkIn(Long appointmentId, Long branchId, Instant at) {
        Objects.requireNonNull(appointmentId, "appointmentId");
        Objects.requireNonNull(branchId, "branchId");
        Objects.requireNonNull(at, "at");
        requireWritableTransaction("checkIn");

        Appointment appointment = appointments.findByIdForUpdate(appointmentId)
                .orElseThrow(() -> new ResourceNotFoundException(FSM_NAME, appointmentId));
        // 409 trước 400 (docs/adr/0046): lệch có chủ đích so với convention 05, như docs/adr/0045.
        transitions.validateTransition(appointment.getStatus(), AppointmentStatus.CHECKED_IN);
        if (!appointment.getBranchId().equals(branchId)) {
            throw new BusinessRuleViolationException("BR-TN-02", "Lịch hẹn thuộc chi nhánh khác");
        }
        int early = configs.getInt(ConfigKey.VISIT_CHECK_IN_EARLY_MINUTES);
        int noShowAfter = configs.getInt(ConfigKey.APPOINTMENT_NO_SHOW_AFTER_MINUTES);
        if (!CheckInWindow.at(at, early, noShowAfter).contains(appointment.getSlotDate(), appointment.getSlotStart())) {
            throw new BusinessRuleViolationException("BR-TN-02", "Chỉ tiếp nhận lịch hẹn của hôm nay, sớm nhất " + early
                    + " phút trước giờ hẹn và trễ nhất " + noShowAfter + " phút sau giờ hẹn");
        }

        appointment.checkIn();
        Appointment saved = appointments.saveAndFlush(appointment);
        return new CheckInInfo(saved.getId(), saved.getCustomerId(), saved.getPetId(), saved.getServiceId(),
                saved.getServiceGroup(),
                saved.getSlotDate().atTime(saved.getSlotStart()).atZone(TimeConfig.BUSINESS_ZONE).toInstant());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void completeByVisit(Long appointmentId) {
        Objects.requireNonNull(appointmentId, "appointmentId");
        requireWritableTransaction("completeByVisit");

        Appointment appointment = lockExisting(appointmentId);
        transitions.validateTransition(appointment.getStatus(), AppointmentStatus.COMPLETED);
        appointment.complete();
        appointments.saveAndFlush(appointment);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelByVisit(Long appointmentId, Instant at) {
        Objects.requireNonNull(appointmentId, "appointmentId");
        Objects.requireNonNull(at, "at");
        requireWritableTransaction("cancelByVisit");

        Appointment appointment = lockExisting(appointmentId);
        // Tường minh, không qua FSM: cạnh BOOKED → CANCELLED có trong map nhưng thuộc #4 / #5, không phải #8.
        if (appointment.getStatus() != AppointmentStatus.CHECKED_IN) {
            throw new InvalidStateTransitionException(FSM_NAME, appointment.getStatus().name(),
                    AppointmentStatus.CANCELLED.name());
        }
        transitions.validateTransition(AppointmentStatus.CHECKED_IN, AppointmentStatus.CANCELLED);
        appointment.cancel(AppointmentCancelSource.STAFF, null, at.truncatedTo(ChronoUnit.MICROS), false);
        appointments.saveAndFlush(appointment);
    }

    /** {@code visits.appointment_id} có FK nên lịch phải tồn tại: thiếu là dữ liệu hỏng (500), không phải 404. */
    private Appointment lockExisting(Long appointmentId) {
        return appointments.findByIdForUpdate(appointmentId).orElseThrow(
                () -> new IllegalStateException("Visit trỏ tới lịch hẹn không tồn tại: " + appointmentId));
    }

    private static void requireWritableTransaction(String method) {
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("AppointmentApi." + method + " cần transaction ghi, không được readOnly");
        }
    }
}
