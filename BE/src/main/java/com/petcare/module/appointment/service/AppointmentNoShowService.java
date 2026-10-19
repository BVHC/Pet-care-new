package com.petcare.module.appointment.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.petcare.module.appointment.api.BookingRestrictionApi;
import com.petcare.module.appointment.entity.Appointment;
import com.petcare.module.appointment.entity.AppointmentStatus;
import com.petcare.module.appointment.fsm.AppointmentTransitionHandler;
import com.petcare.module.appointment.repository.AppointmentRepository;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;

/**
 * ST05 — chuyển lịch hẹn quá giờ sang {@code NO_SHOW} (Lịch hẹn#6, command {@code markNoShow}; BR-LH-08, BR-LH-09;
 * docs/adr/0048). Chỉ {@code appointment/job/NoShowJob} gọi.
 * <p>
 * Mỗi {@link #markNoShow} là một transaction cho một lịch (convention 07 §7.4), READ COMMITTED (bắt buộc: ADR-0045 §7),
 * không {@code noRollbackFor}, không {@code try/catch}: lỗi ở bất kỳ bước nào (kể cả {@code BoardingQueryApi} chưa cài —
 * nợ D033) rollback cả việc chuyển trạng thái, vì NO_SHOW là vi phạm phải được đánh giá cùng transaction (06 §4).
 * Thứ tự: khóa dòng có điều kiện ({@code SKIP LOCKED}) → đọc dưới khóa → kiểm lại mốc quá hạn → {@code now} sau khóa →
 * FSM → đổi trạng thái → {@code saveAndFlush} → {@code evaluateAfterViolation} (advisory 1002, khóa cuối cùng). Không
 * khóa thú / chi nhánh: chỉ trả quota của khung đã qua.
 */
@Service
public class AppointmentNoShowService {

    /** Kết quả xử lý một lịch. */
    public enum NoShowResult {
        /** Đã chuyển {@code NO_SHOW} và đã đánh giá BR-LH-09. */
        MARKED,
        /** Không làm gì: đã rời {@code BOOKED}, chưa quá hạn, hoặc đang bị luồng khác khóa — lượt sau xét lại. */
        SKIPPED
    }

    private final AppointmentRepository appointments;
    private final AppointmentTransitionHandler transitions;
    private final BookingRestrictionApi restrictions;
    private final SystemConfigApi configs;
    private final Clock clock;

    public AppointmentNoShowService(AppointmentRepository appointments, AppointmentTransitionHandler transitions,
            BookingRestrictionApi restrictions, SystemConfigApi configs, Clock clock) {
        this.appointments = appointments;
        this.transitions = transitions;
        this.restrictions = restrictions;
        this.configs = configs;
        this.clock = clock;
    }

    /** Mốc quá hạn tại {@code now}; [CFG] đọc lúc chạy, một lần mỗi lượt (chính sách hiện hành, docs/adr/0048 §2). */
    public NoShowCutoff cutoff(Instant now) {
        return NoShowCutoff.at(now, configs.getInt(ConfigKey.APPOINTMENT_NO_SHOW_AFTER_MINUTES));
    }

    /** Tối đa {@code limit} id lịch {@code BOOKED} quá hạn, {@code id > afterId}, tăng dần. Không khóa. */
    @Transactional(readOnly = true)
    public List<Long> findOverdueIds(NoShowCutoff cutoff, long afterId, int limit) {
        Objects.requireNonNull(cutoff, "cutoff");
        return appointments.findOverdueBookedIds(cutoff.date(), cutoff.time().toString(), afterId, limit);
    }

    @Transactional
    public NoShowResult markNoShow(long appointmentId, NoShowCutoff cutoff) {
        Objects.requireNonNull(cutoff, "cutoff");
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("markNoShow cần transaction ghi, không được readOnly");
        }
        if (appointments.lockOverdueBooked(appointmentId, cutoff.date(), cutoff.time().toString()).isEmpty()) {
            return NoShowResult.SKIPPED;
        }
        Appointment appointment = appointments.findById(appointmentId).orElseThrow(() -> new IllegalStateException(
                "Lịch hẹn #" + appointmentId + " đã khóa nhưng không đọc được"));
        if (!cutoff.isOverdue(appointment.getSlotDate(), appointment.getSlotStart())) {
            throw new IllegalStateException("Lịch hẹn #" + appointmentId + " chưa quá hạn NO_SHOW nhưng đã được chọn");
        }
        Instant at = clock.instant().truncatedTo(ChronoUnit.MICROS);
        transitions.validateTransition(appointment.getStatus(), AppointmentStatus.NO_SHOW);
        appointment.markNoShow();
        Appointment saved = appointments.saveAndFlush(appointment);
        restrictions.evaluateAfterViolation(saved.getCustomerId(), at);
        return NoShowResult.MARKED;
    }
}
