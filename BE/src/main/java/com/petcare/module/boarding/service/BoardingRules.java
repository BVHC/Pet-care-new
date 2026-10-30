package com.petcare.module.boarding.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import com.petcare.module.branch.api.BranchQueryApi.TimeRange;
import com.petcare.platform.config.TimeConfig;

/** Hàm thuần của BR-LT-04, BR-LT-06 và ST05 (docs/adr/0065); không đọc [CFG] hay đồng hồ. */
final class BoardingRules {

    private static final DateTimeFormatter NIGHT = DateTimeFormatter.ofPattern("dd/MM");

    private BoardingRules() {
    }

    /** Khách đặt online: {@code today + minLeadDays ≤ checkIn ≤ today + maxAdvanceDays} (BR-LT-04). */
    static boolean customerWindowAllows(LocalDate today, LocalDate checkIn, int minLeadDays, int maxAdvanceDays) {
        return !checkIn.isBefore(today.plusDays(minLeadDays)) && !checkIn.isAfter(today.plusDays(maxAdvanceDays));
    }

    /** Từ mốc này trở đi, hủy là hủy muộn: 00:00 giờ Việt Nam của ngày nhận trừ số giờ [CFG] (BR-LT-06, D4). */
    static Instant lateCancelFrom(LocalDate checkIn, int lateCancelHours) {
        return checkIn.atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant().minus(Duration.ofHours(lateCancelHours));
    }

    /** Giờ Việt Nam của {@code now} nằm trong một khung giờ mở cửa {@code [open, close)} (BR-LT-04). */
    static boolean withinOpeningHours(Instant now, List<TimeRange> ranges) {
        LocalTime time = now.atZone(TimeConfig.BUSINESS_ZONE).toLocalTime();
        return ranges.stream().anyMatch(r -> !time.isBefore(r.open()) && time.isBefore(r.close()));
    }

    /** Giờ Việt Nam của {@code now} sau giờ trả quy định (BR-LT-04); đúng giờ đó chưa tính trễ. */
    static boolean afterCheckoutTime(Instant now, LocalTime checkoutTime) {
        return now.atZone(TimeConfig.BUSINESS_ZONE).toLocalTime().isAfter(checkoutTime);
    }

    /**
     * Số đêm tính tiền (BR-LT-09): ngày kết thúc − ngày nhận thực tế, tối thiểu 1; trả sau giờ quy định cộng 1 đêm
     * (docs/adr/0086 F1). Thú mất (BR-LT-12) không cộng đêm trễ.
     */
    static int chargedNights(LocalDate checkInDate, LocalDate endDate, boolean afterCheckoutTime) {
        long nights = java.time.temporal.ChronoUnit.DAYS.between(checkInDate, endDate) + (afterCheckoutTime ? 1 : 0);
        return (int) Math.max(1, nights);
    }

    static Optional<LocalTime> closingTime(List<TimeRange> ranges) {
        return ranges.stream().map(TimeRange::close).max(Comparator.naturalOrder());
    }

    /**
     * Hết giờ làm việc của ngày nhận (ST05, Đặt chỗ#6): giờ đóng cửa muộn nhất; ngày không có khung giờ thì 00:00 ngày
     * kế tiếp.
     */
    static Instant noShowCutoff(LocalDate checkIn, List<TimeRange> ranges) {
        return closingTime(ranges)
                .map(close -> checkIn.atTime(close).atZone(TimeConfig.BUSINESS_ZONE).toInstant())
                .orElseGet(() -> checkIn.plusDays(1).atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant());
    }

    static String nightList(List<LocalDate> nights) {
        return nights.stream().map(NIGHT::format).collect(Collectors.joining(", "));
    }
}
