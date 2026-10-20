package com.petcare.module.appointment.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

import com.petcare.platform.config.TimeConfig;

/**
 * Mốc quá hạn của ST05 (BR-LH-08, docs/adr/0053). Lớp thuần, đối ngẫu của {@link CheckInWindow}: lịch còn check-in được
 * khi {@code now ≤ giờ hẹn + noShowAfter} (biên tính là được), nên lịch chỉ quá hạn khi
 * {@code giờ hẹn + noShowAfter < now}, tức {@code (slot_date, slot_start) < (date, time)} với
 * {@code (date, time) = now − noShowAfter} theo giờ Việt Nam. Qua nửa đêm tự đúng vì {@code date} có thể là hôm trước.
 * <p>
 * {@code time} cắt về micro giây như cột {@code TIMESTAMPTZ} / {@code TIME} (pgjdbc làm tròn nano, cùng lý do ở
 * {@link CheckInWindow}); cắt xuống chỉ làm mốc sớm hơn tối đa 1 µs, phía an toàn (không NO_SHOW lịch còn check-in được).
 *
 * @param date mốc ngày (giờ Việt Nam)
 * @param time mốc giờ trong {@code date}
 */
public record NoShowCutoff(LocalDate date, LocalTime time) {

    public NoShowCutoff {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(time, "time");
    }

    public static NoShowCutoff at(Instant now, int noShowAfterMinutes) {
        Objects.requireNonNull(now, "now");
        if (noShowAfterMinutes < 0) {
            throw new IllegalArgumentException("Số phút âm: noShowAfter=" + noShowAfterMinutes);
        }
        LocalDateTime cutoff = LocalDateTime.ofInstant(now, TimeConfig.BUSINESS_ZONE)
                .minusMinutes(noShowAfterMinutes)
                .truncatedTo(ChronoUnit.MICROS);
        return new NoShowCutoff(cutoff.toLocalDate(), cutoff.toLocalTime());
    }

    /** Khung {@code slotDate slotStart} đã quá hạn NO_SHOW (nghiêm ngặt). */
    public boolean isOverdue(LocalDate slotDate, LocalTime slotStart) {
        Objects.requireNonNull(slotDate, "slotDate");
        Objects.requireNonNull(slotStart, "slotStart");
        int byDate = slotDate.compareTo(date);
        return byDate < 0 || (byDate == 0 && slotStart.isBefore(time));
    }
}
