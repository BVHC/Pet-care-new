package com.petcare.module.appointment.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.petcare.module.appointment.entity.Appointment;
import com.petcare.module.appointment.entity.AppointmentCancelSource;
import com.petcare.module.appointment.entity.AppointmentStatus;
import com.petcare.module.appointment.fsm.AppointmentTransitionHandler;
import com.petcare.module.appointment.repository.AppointmentRepository;

/**
 * Lịch hẹn#5 — phòng khám hủy ({@code BOOKED → CANCELLED}, command {@code cancelByClinic}; BR-LH-10, BR-CN-04,
 * BR-KH-05, BR-KH-08; docs/adr/0052). Chỉ gọi từ listener của {@code appointment/listener} trong transaction của bên phát
 * sự kiện (branch thêm ngày nghỉ / thu hẹp giờ, customer đánh dấu thú mất / chuyển chủ): {@code MANDATORY}, không
 * {@code noRollbackFor}, không bắt lỗi — lỗi ở đây rollback cả use case của bên phát (03 dòng 11).
 * <p>
 * Điều kiện được phép hủy do bên phát kiểm (convention 07 dòng 41); ở đây chỉ kiểm lại {@code status} <b>sau khóa</b>,
 * vì danh sách id được tính trước khi khóa dòng {@code appointments}: lịch đã rời {@code BOOKED} (khách tự hủy, đã
 * check-in) thì bỏ qua, không lỗi. Thứ tự: khử trùng, sắp id tăng dần → khóa từng lô ≤ {@value #BATCH} id trong một câu
 * → {@code now} sau khóa → {@code validateTransition} → {@code cancel(CLINIC, lý do, now, false)} → {@code saveAndFlush}
 * → thông báo. Không khóa thú (docs/adr/0052 Q1: customer lấy khóa thú trước, nợ D027), không khóa chi nhánh, không
 * tăng số lần đổi, không gọi {@code BookingRestrictionApi} (hủy do phòng khám không tính BR-LH-09), không audit, không
 * {@code BranchScope} (API nội bộ: thú dùng chung toàn chuỗi, lịch ở chi nhánh khác vẫn bị hủy).
 */
@Service
public class AppointmentClinicCancellationService {

    static final int BATCH = 100;

    private final AppointmentRepository appointments;
    private final AppointmentTransitionHandler transitions;
    private final ClinicCancellationNotices notices;
    private final Clock clock;

    public AppointmentClinicCancellationService(AppointmentRepository appointments,
            AppointmentTransitionHandler transitions, ClinicCancellationNotices notices, Clock clock) {
        this.appointments = appointments;
        this.transitions = transitions;
        this.notices = notices;
        this.clock = clock;
    }

    /**
     * Hủy các lịch {@code appointmentIds} còn {@code BOOKED} sau khóa; trả id các lịch đã hủy (tăng dần). Id không tồn
     * tại → {@link IllegalStateException} (lịch hẹn không bao giờ bị xóa cứng: thiếu là dữ liệu hỏng).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Long> cancelByClinic(Collection<Long> appointmentIds, ClinicCancelCause cause) {
        Objects.requireNonNull(appointmentIds, "appointmentIds");
        Objects.requireNonNull(cause, "cause");
        requireWritableTransaction();
        List<Long> ids = List.copyOf(new TreeSet<>(appointmentIds));
        if (ids.isEmpty()) {
            return List.of();
        }

        List<Appointment> locked = new ArrayList<>(ids.size());
        for (int from = 0; from < ids.size(); from += BATCH) {
            List<Long> batch = ids.subList(from, Math.min(ids.size(), from + BATCH));
            List<Appointment> rows = appointments.findAllByIdInForUpdate(batch);
            if (rows.size() != batch.size()) {
                throw new IllegalStateException("Hủy do phòng khám trỏ tới lịch hẹn không tồn tại: " + batch);
            }
            locked.addAll(rows);
        }

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        List<Appointment> cancelled = new ArrayList<>(locked.size());
        for (Appointment appointment : locked) {
            if (appointment.getStatus() != AppointmentStatus.BOOKED) {
                continue;
            }
            transitions.validateTransition(AppointmentStatus.BOOKED, AppointmentStatus.CANCELLED);
            appointment.cancel(AppointmentCancelSource.CLINIC, cause.reason(), now, false);
            cancelled.add(appointments.saveAndFlush(appointment));
        }
        notices.enqueueFor(cancelled, cause);
        return cancelled.stream().map(Appointment::getId).toList();
    }

    /** Hủy mọi lịch {@code BOOKED} của thú, kể cả lịch đã quá giờ mà ST05 chưa chuyển {@code NO_SHOW} (Q3). */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Long> cancelBookedOfPet(Long petId, ClinicCancelCause cause) {
        Objects.requireNonNull(petId, "petId");
        requireWritableTransaction();
        return cancelByClinic(appointments.findBookedIdsByPetId(petId), cause);
    }

    private static void requireWritableTransaction() {
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("cancelByClinic cần transaction ghi, không được readOnly");
        }
    }
}
