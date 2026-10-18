package com.petcare.module.appointment.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Objects;

import com.petcare.platform.config.TimeConfig;

/**
 * Cửa sổ check-in của lịch hẹn tại thời điểm {@code now} (docs/adr/0044 §6). Lớp thuần, dùng chung cho bộ lọc
 * {@code checkInReady} (appointment-v1 #3), tiếp nhận Visit#1 (T26) và ST05 NO_SHOW (T25).
 * <ul>
 * <li>BR-TN-02: lịch của <b>hôm nay</b> (giờ Việt Nam), check-in sớm nhất {@code visit.check_in_early_minutes} [CFG]
 * trước giờ hẹn: {@code giờ hẹn − early ≤ now}.</li>
 * <li>BR-TN-03, BR-LH-08: "quá" {@code appointment.no_show_after_minutes} [CFG] là NO_SHOW, nên còn check-in được khi
 * {@code now ≤ giờ hẹn + noShowAfter} (biên tính là được).</li>
 * </ul>
 * Quy về điều kiện trên giờ bắt đầu khung: {@code earliestStart ≤ slotStart ≤ latestStart}. Mốc dưới rơi sang hôm trước
 * thì kẹp về {@code 00:00}; mốc trên rơi sang hôm sau thì {@code latestStart = null} (không chặn trên), không dùng
 * {@code LocalTime.MAX} vì pgjdbc làm tròn nano thành {@code 24:00:00}.
 *
 * @param date          ngày hẹn hợp lệ (hôm nay theo giờ Việt Nam)
 * @param earliestStart giờ bắt đầu khung sớm nhất còn check-in được (không null)
 * @param latestStart   giờ bắt đầu khung muộn nhất đã check-in được; {@code null} = không chặn trên
 */
public record CheckInWindow(LocalDate date, LocalTime earliestStart, LocalTime latestStart) {

    public CheckInWindow {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(earliestStart, "earliestStart");
    }

    public static CheckInWindow at(Instant now, int earlyMinutes, int noShowAfterMinutes) {
        Objects.requireNonNull(now, "now");
        if (earlyMinutes < 0 || noShowAfterMinutes < 0) {
            throw new IllegalArgumentException("Số phút âm: early=" + earlyMinutes + ", noShowAfter="
                    + noShowAfterMinutes);
        }
        LocalDateTime local = LocalDateTime.ofInstant(now, TimeConfig.BUSINESS_ZONE);
        LocalDate today = local.toLocalDate();
        LocalDateTime lower = local.minusMinutes(noShowAfterMinutes);
        LocalDateTime upper = local.plusMinutes(earlyMinutes);
        LocalTime earliest = lower.toLocalDate().isBefore(today) ? LocalTime.MIN : lower.toLocalTime();
        LocalTime latest = upper.toLocalDate().isAfter(today) ? null : upper.toLocalTime();
        return new CheckInWindow(today, earliest, latest);
    }

    /** Khung {@code slotDate} {@code slotStart} nằm trong cửa sổ (chưa xét trạng thái, chi nhánh). */
    public boolean contains(LocalDate slotDate, LocalTime slotStart) {
        return date.equals(slotDate) && !slotStart.isBefore(earliestStart)
                && (latestStart == null || !slotStart.isAfter(latestStart));
    }
}
