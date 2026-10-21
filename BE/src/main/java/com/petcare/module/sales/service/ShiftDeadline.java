package com.petcare.module.sales.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;

import com.petcare.module.branch.api.BranchQueryApi.TimeRange;

/**
 * Hạn tự chốt <b>ca thường</b> (ST13; Ca thu ngân#4; BR-TG-05; docs/adr/0064): giờ đóng của khoảng giờ mở cửa cuối
 * cùng của ngày mở ca + {@code cashier_shift.auto_close_grace_minutes} [CFG]. Ngày không còn khoảng giờ nào (ngày nghỉ
 * khai báo sau khi đã mở ca) thì hạn là 00:00 ngày kế tiếp, để không chốt giữa lúc đang làm việc. Hạn của ca ngoài giờ
 * là {@code BranchQueryApi.nextOpeningStart} nên không có ở đây.
 */
final class ShiftDeadline {

    private ShiftDeadline() {
    }

    static LocalDateTime regular(LocalDate businessDate, List<TimeRange> ranges, int graceMinutes) {
        return ranges.stream().map(TimeRange::close).max(Comparator.naturalOrder())
                .map(close -> businessDate.atTime(close).plusMinutes(graceMinutes))
                .orElseGet(() -> businessDate.plusDays(1).atTime(LocalTime.MIDNIGHT));
    }
}
