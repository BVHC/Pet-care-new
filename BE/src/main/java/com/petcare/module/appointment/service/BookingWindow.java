package com.petcare.module.appointment.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;

import com.petcare.platform.config.TimeConfig;

/**
 * Khoảng thời gian được đặt lịch theo BR-LH-04 (docs/adr/0042 mục Q1), tính từ một thời điểm {@code now} cố định của
 * request. Lớp thuần, dùng chung cho xem khung trống (#1) và đặt / đổi lịch (#2, #5).
 * <ul>
 * <li><b>Khách:</b> ngày hẹn trong {@code [hôm nay, hôm nay + maxAdvanceDays]} theo giờ Việt Nam (mốc xa nhất tính
 * <b>theo ngày</b>), và giờ bắt đầu khung {@code ≥ now + minLeadHours} (mốc gần nhất tính <b>theo thời điểm</b>, đúng
 * {@code minLeadHours} giờ vẫn được).</li>
 * <li><b>Lễ tân:</b> mọi khung <b>chưa bắt đầu</b>: giờ bắt đầu {@code > now} (đúng giờ bắt đầu là đã bắt đầu); không
 * giới hạn xa.</li>
 * </ul>
 * Ngày, giờ hiểu theo {@link TimeConfig#BUSINESS_ZONE}, không theo múi giờ của {@code Clock}.
 */
public final class BookingWindow {

    private final Instant now;
    private final LocalDate today;
    private final LocalDate lastDate;
    private final Instant earliestStart;
    private final boolean startInclusive;

    private BookingWindow(Instant now, LocalDate lastDate, Instant earliestStart, boolean startInclusive) {
        this.now = now;
        this.today = LocalDate.ofInstant(now, TimeConfig.BUSINESS_ZONE);
        this.lastDate = lastDate;
        this.earliestStart = earliestStart;
        this.startInclusive = startInclusive;
    }

    /** Khách đặt online: {@code appointment.min_lead_hours}, {@code appointment.max_advance_days} [CFG]. */
    public static BookingWindow forCustomer(Instant now, int minLeadHours, int maxAdvanceDays) {
        Objects.requireNonNull(now, "now");
        if (minLeadHours < 0 || maxAdvanceDays < 0) {
            throw new IllegalArgumentException("Tham số cửa sổ đặt lịch âm: " + minLeadHours + ", " + maxAdvanceDays);
        }
        LocalDate today = LocalDate.ofInstant(now, TimeConfig.BUSINESS_ZONE);
        return new BookingWindow(now, today.plusDays(maxAdvanceDays), now.plus(Duration.ofHours(minLeadHours)), true);
    }

    /** Lễ tân đặt hộ: mọi khung chưa bắt đầu. */
    public static BookingWindow forStaff(Instant now) {
        Objects.requireNonNull(now, "now");
        return new BookingWindow(now, null, now, false);
    }

    /** Ngày có thể chứa khung đặt được (không quá khứ; với khách: không quá mốc xa nhất). */
    public boolean mayContain(LocalDate date) {
        Objects.requireNonNull(date, "date");
        return !date.isBefore(today) && (lastDate == null || !date.isAfter(lastDate));
    }

    /** Khung bắt đầu ở {@code start} ngày {@code date} nằm trong cửa sổ. */
    public boolean allows(LocalDate date, LocalTime start) {
        Objects.requireNonNull(start, "start");
        if (!mayContain(date)) {
            return false;
        }
        Instant slotStart = date.atTime(start).atZone(TimeConfig.BUSINESS_ZONE).toInstant();
        return startInclusive ? !slotStart.isBefore(earliestStart) : slotStart.isAfter(earliestStart);
    }

    /** Thời điểm cửa sổ được tính. */
    public Instant now() {
        return now;
    }
}
