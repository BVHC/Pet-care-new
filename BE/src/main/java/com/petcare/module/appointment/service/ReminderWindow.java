package com.petcare.module.appointment.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

import com.petcare.platform.config.TimeConfig;

/**
 * Cửa sổ nhắc của ST03 (BR-LH-12, docs/adr/0056): lịch được nhắc khi {@code from < giờ hẹn ≤ to}, với
 * {@code from = now} và {@code to = now + hoursBefore} theo giờ Việt Nam (không có DST nên cộng giờ trên giờ địa phương
 * là đúng). Cận dưới nghiêm ngặt: lịch đã bắt đầu không nhắc nữa (Q4; ST05 có thể chưa chuyển nó sang {@code NO_SHOW}).
 * Cận trên tính là được: đúng {@code hoursBefore} giờ trước giờ hẹn là đến hạn nhắc. Lớp thuần.
 * <p>
 * Hai mốc cắt về micro giây như cột {@code TIME} (khuôn {@link NoShowCutoff}). Cắt {@code from} xuống làm cận dưới sớm
 * hơn tối đa 1 µs — phía bao trùm; vì vậy dưới khóa còn kiểm lại {@link #notStartedAt} với {@code now} mới.
 *
 * @param from mốc dưới (loại trừ), giờ Việt Nam
 * @param to   mốc trên (bao gồm), giờ Việt Nam
 */
public record ReminderWindow(LocalDateTime from, LocalDateTime to) {

    public ReminderWindow {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("Cửa sổ nhắc rỗng: from=" + from + " to=" + to);
        }
    }

    public static ReminderWindow at(Instant now, int hoursBefore) {
        Objects.requireNonNull(now, "now");
        if (hoursBefore <= 0) {
            throw new IllegalArgumentException("Số giờ nhắc phải dương: hoursBefore=" + hoursBefore);
        }
        LocalDateTime from = local(now);
        return new ReminderWindow(from, from.plusHours(hoursBefore));
    }

    /** Khung {@code slotDate slotStart} nằm trong {@code (from, to]}. */
    public boolean contains(LocalDate slotDate, LocalTime slotStart) {
        LocalDateTime slot = slotDate.atTime(slotStart);
        return slot.isAfter(from) && !slot.isAfter(to);
    }

    /** Lịch chưa bắt đầu tại {@code now} (kiểm lại cận dưới với thời điểm sau khóa). */
    public static boolean notStartedAt(Instant now, LocalDate slotDate, LocalTime slotStart) {
        return slotDate.atTime(slotStart).isAfter(local(now));
    }

    public LocalDate fromDate() {
        return from.toLocalDate();
    }

    /** {@code HH:mm:ss.SSSSSS} cho native query ({@code CAST(:t AS time)}). */
    public String fromTime() {
        return from.toLocalTime().toString();
    }

    public LocalDate toDate() {
        return to.toLocalDate();
    }

    public String toTime() {
        return to.toLocalTime().toString();
    }

    private static LocalDateTime local(Instant instant) {
        return LocalDateTime.ofInstant(instant, TimeConfig.BUSINESS_ZONE).truncatedTo(ChronoUnit.MICROS);
    }
}
