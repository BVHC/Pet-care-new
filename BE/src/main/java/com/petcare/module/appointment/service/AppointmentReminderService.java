package com.petcare.module.appointment.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.petcare.module.appointment.repository.AppointmentRepository;
import com.petcare.module.appointment.repository.AppointmentRepository.ReminderRow;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;

/**
 * ST03 — nhắc lịch hẹn trước giờ hẹn (BR-LH-12; docs/adr/0056; command {@code remindAppointment}, không đổi trạng thái
 * nên không có dòng trong 03 §3). Chỉ {@code appointment/job/AppointmentReminderJob} gọi.
 * <p>
 * Mỗi {@link #remind} là một transaction cho một lịch (convention 07 §7.4), READ COMMITTED, không {@code noRollbackFor},
 * không {@code try/catch}. Thứ tự: khóa dòng có điều kiện ({@code SKIP LOCKED} — ST03 không bao giờ chờ khóa dòng nên
 * không thể nằm trong vòng deadlock) → đọc projection dưới khóa → {@code now} sau khóa, kiểm lại "chưa bắt đầu" →
 * {@code UPDATE} có điều kiện chỉ hai cột {@code reminded_at}, {@code updated_at} (không ghi qua entity: không bao giờ
 * đè khung giờ / trạng thái của luồng khác, Q7) → ghi outbox trong cùng transaction. Đặt {@code reminded_at} cùng
 * transaction với {@code enqueue} là điều kiện để không nhắc trùng; outbox lỗi thì rollback cả {@code reminded_at} và
 * lượt sau nhắc lại. Không khóa thú / chi nhánh, không audit, không FSM.
 */
@Service
public class AppointmentReminderService {

    /** Kết quả xử lý một lịch. */
    public enum ReminderResult {
        /** Đã đặt {@code reminded_at} và ghi outbox. */
        REMINDED,
        /** Đã đặt {@code reminded_at}, không gửi: hồ sơ không có kênh nào (Q3) hoặc thú đã mất (Q10). */
        NOT_SENT,
        /** Không làm gì: đã nhắc, đã rời {@code BOOKED}, ngoài cửa sổ, đã bắt đầu, hoặc đang bị luồng khác khóa. */
        SKIPPED
    }

    private static final Logger log = LoggerFactory.getLogger(AppointmentReminderService.class);

    private final AppointmentRepository appointments;
    private final AppointmentReminderNotices notices;
    private final SystemConfigApi configs;
    private final Clock clock;

    public AppointmentReminderService(AppointmentRepository appointments, AppointmentReminderNotices notices,
            SystemConfigApi configs, Clock clock) {
        this.appointments = appointments;
        this.notices = notices;
        this.configs = configs;
        this.clock = clock;
    }

    /** Cửa sổ nhắc tại {@code now}; [CFG] đọc lúc chạy, một lần mỗi lượt (giả định A1, như docs/adr/0053 §2). */
    public ReminderWindow window(Instant now) {
        return ReminderWindow.at(now, configs.getInt(ConfigKey.APPOINTMENT_REMINDER_HOURS_BEFORE));
    }

    /**
     * Tối đa {@code limit} id lịch đến hạn nhắc theo thứ tự khung giờ rồi id; {@code afterId} = id cuối của lô trước,
     * {@code 0} = lô đầu. Không khóa.
     */
    @Transactional(readOnly = true)
    public List<Long> findDueIds(ReminderWindow window, long afterId, int limit) {
        Objects.requireNonNull(window, "window");
        return afterId == 0
                ? appointments.findDueForReminderIds(window.fromDate(), window.fromTime(), window.toDate(),
                        window.toTime(), limit)
                : appointments.findDueForReminderIdsAfter(window.fromDate(), window.fromTime(), window.toDate(),
                        window.toTime(), afterId, limit);
    }

    @Transactional
    public ReminderResult remind(long appointmentId, ReminderWindow window) {
        Objects.requireNonNull(window, "window");
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("remind cần transaction ghi, không được readOnly");
        }
        if (appointments.lockDueForReminder(appointmentId, window.fromDate(), window.fromTime(), window.toDate(),
                window.toTime()).isEmpty()) {
            return ReminderResult.SKIPPED;
        }
        ReminderRow row = appointments.findReminderRow(appointmentId).orElseThrow(() -> new IllegalStateException(
                "Lịch hẹn #" + appointmentId + " đã khóa nhưng không đọc được"));
        if (!window.contains(row.getSlotDate(), row.getSlotStart())) {
            throw new IllegalStateException("Lịch hẹn #" + appointmentId + " ngoài cửa sổ nhắc nhưng đã được chọn");
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (!ReminderWindow.notStartedAt(now, row.getSlotDate(), row.getSlotStart())) {
            return ReminderResult.SKIPPED;
        }
        int updated = appointments.markRemindedIfDue(appointmentId, now);
        if (updated != 1) {
            throw new IllegalStateException("Lịch hẹn #" + appointmentId + " đã khóa nhưng đánh dấu nhắc được "
                    + updated + " dòng");
        }
        return switch (notices.enqueueFor(row)) {
            case SENT -> ReminderResult.REMINDED;
            case NO_CHANNEL -> {
                log.info("APPOINTMENT_REMINDER_NO_CHANNEL appointmentId={} customerId={}", appointmentId,
                        row.getCustomerId());
                yield ReminderResult.NOT_SENT;
            }
            case PET_DECEASED -> {
                log.warn("APPOINTMENT_REMINDER_PET_DECEASED appointmentId={} petId={}", appointmentId, row.getPetId());
                yield ReminderResult.NOT_SENT;
            }
        };
    }
}
