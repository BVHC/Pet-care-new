package com.petcare.module.appointment.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.appointment.api.BookingRestrictionApi;
import com.petcare.module.appointment.entity.BookingRestriction;
import com.petcare.module.appointment.repository.AppointmentRepository;
import com.petcare.module.appointment.repository.BookingRestrictionRepository;
import com.petcare.module.boarding.api.BoardingQueryApi;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;

/**
 * Cài {@link BookingRestrictionApi} (BR-LH-09): đọc {@link #isRestricted} (docs/adr/0043) và ghi
 * {@link #evaluateAfterViolation} (docs/adr/0045, trả nợ D030).
 */
@Service
public class BookingRestrictionService implements BookingRestrictionApi {

    private final BookingRestrictionRepository restrictions;
    private final AppointmentRepository appointments;
    private final AppointmentLocks locks;
    private final BoardingQueryApi boarding;
    private final SystemConfigApi configs;

    public BookingRestrictionService(BookingRestrictionRepository restrictions, AppointmentRepository appointments,
            AppointmentLocks locks, BoardingQueryApi boarding, SystemConfigApi configs) {
        this.restrictions = restrictions;
        this.appointments = appointments;
        this.locks = locks;
        this.boarding = boarding;
        this.configs = configs;
    }

    /** {@code starts_at ≤ at < ends_at} và chưa gỡ sớm (erd §5). Tham gia transaction của bên gọi nếu có. */
    @Override
    @Transactional(readOnly = true)
    public boolean isRestricted(Long customerId, Instant at) {
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(at, "at");
        return restrictions.existsActive(customerId, at);
    }

    /**
     * Gọi ngay sau một vi phạm (hủy muộn, NO_SHOW — của lịch hẹn hoặc đặt chỗ), trong transaction của bên gọi, sau khi
     * vi phạm đã được flush (docs/adr/0045):
     * <ol>
     * <li>khóa theo khách (advisory 1002, luôn là khóa cuối cùng) — hai vi phạm đồng thời không tạo hai dòng;</li>
     * <li>đang bị hạn chế → không tạo thêm;</li>
     * <li>đếm vi phạm xảy ra <b>sau</b> {@code max(at − window_days, starts_at của lần hạn chế gần nhất)}: mỗi vi phạm
     * chỉ góp vào một lần hạn chế (giả định Q3, TBD cho PO), vi phạm đã gây ra lần trước không bị đếm lại;</li>
     * <li>đủ {@code violation_threshold} [CFG] → hạn chế từ {@code at} tới {@code at + duration_days} [CFG].</li>
     * </ol>
     * READ COMMITTED (mặc định), không REPEATABLE READ: câu đếm chạy sau khi có khóa nên thấy vi phạm của transaction vừa
     * nhả khóa. Lỗi ở đây (kể cả {@link BoardingQueryApi} chưa cài — nợ D033) làm rollback cả vi phạm của bên gọi.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void evaluateAfterViolation(Long customerId, Instant at) {
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(at, "at");
        locks.lockCustomerViolations(customerId);
        if (restrictions.existsActive(customerId, at)) {
            return;
        }
        Instant windowStart = at.minus(Duration.ofDays(configs.getInt(ConfigKey.BOOKING_RESTRICTION_WINDOW_DAYS)));
        Instant since = restrictions.findLatestStartsAt(customerId)
                .filter(latest -> latest.isAfter(windowStart))
                .orElse(windowStart);
        int violations = appointments.countViolationsAfter(customerId, since)
                + boarding.countViolations(customerId, since);
        if (violations < configs.getInt(ConfigKey.BOOKING_RESTRICTION_VIOLATION_THRESHOLD)) {
            return;
        }
        Instant endsAt = at.plus(Duration.ofDays(configs.getInt(ConfigKey.BOOKING_RESTRICTION_DURATION_DAYS)));
        restrictions.saveAndFlush(BookingRestriction.start(customerId, at, endsAt, violations));
    }
}
