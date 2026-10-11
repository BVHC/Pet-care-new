package com.petcare.module.branch.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.petcare.module.branch.api.BranchQueryApi.TimeRange;
import com.petcare.module.branch.dto.DayHoursDto;
import com.petcare.module.branch.dto.OpeningHoursVersionResponse;
import com.petcare.module.branch.dto.TimeRangeDto;
import com.petcare.module.branch.entity.OpeningHours;
import com.petcare.module.branch.schedule.BranchSchedule.Version;
import com.petcare.platform.exception.BusinessRuleViolationException;

/** Chuyển giữa dòng {@code opening_hours}, {@link Version} và DTO; kiểm BR-CN-02 khi nhận giờ từ client. */
final class OpeningHoursMapping {

    static final int DAYS_IN_WEEK = 7;
    static final int MAX_RANGES_PER_DAY = 2;

    private OpeningHoursMapping() {
    }

    /** Gom các dòng của một ngày hiệu lực thành một phiên bản. */
    static Version toVersion(LocalDate effectiveFrom, List<OpeningHours> rows) {
        Map<Integer, List<TimeRange>> days = new TreeMap<>();
        for (OpeningHours row : rows) {
            List<TimeRange> ranges = new ArrayList<>();
            if (row.getOpen1() != null) {
                ranges.add(new TimeRange(row.getOpen1(), row.getClose1()));
            }
            if (row.getOpen2() != null) {
                ranges.add(new TimeRange(row.getOpen2(), row.getClose2()));
            }
            days.put(row.getDayOfWeek(), ranges);
        }
        return new Version(effectiveFrom, days);
    }

    /** Một dòng cho mỗi thứ 1..7 của phiên bản (thứ không có khoảng nào là ngày nghỉ cố định). */
    static List<OpeningHours> toRows(Long branchId, Version version) {
        List<OpeningHours> rows = new ArrayList<>();
        for (int day = 1; day <= DAYS_IN_WEEK; day++) {
            List<TimeRange> ranges = version.days().getOrDefault(day, List.of());
            TimeRange first = ranges.size() > 0 ? ranges.get(0) : null;
            TimeRange second = ranges.size() > 1 ? ranges.get(1) : null;
            rows.add(new OpeningHours(branchId, version.effectiveFrom(), day,
                    first == null ? null : first.open(), first == null ? null : first.close(),
                    second == null ? null : second.open(), second == null ? null : second.close()));
        }
        return rows;
    }

    static OpeningHoursVersionResponse toResponse(Version version) {
        List<DayHoursDto> days = new ArrayList<>();
        for (int day = 1; day <= DAYS_IN_WEEK; day++) {
            List<TimeRangeDto> ranges = version.days().getOrDefault(day, List.of()).stream()
                    .map(r -> new TimeRangeDto(r.open(), r.close())).toList();
            days.add(new DayHoursDto(day, ranges));
        }
        return new OpeningHoursVersionResponse(version.effectiveFrom(), days);
    }

    /**
     * Phiên bản từ request. BR-CN-02: đủ 7 ngày (catalog branch-v1 A5), mỗi ngày 0–2 khoảng, giờ mở trước giờ đóng,
     * khoảng sau không chồng khoảng trước (được phép nối liền, như CHECK {@code close_1 <= open_2}).
     */
    static Version fromRequest(LocalDate effectiveFrom, List<DayHoursDto> days) {
        Set<Integer> seen = new HashSet<>();
        for (DayHoursDto day : days) {
            if (day.dayOfWeek() < 1 || day.dayOfWeek() > DAYS_IN_WEEK) {
                throw new BusinessRuleViolationException("BR-CN-02", "Thứ trong tuần phải từ 1 (thứ Hai) đến 7 (Chủ nhật)");
            }
            if (!seen.add(day.dayOfWeek())) {
                throw new BusinessRuleViolationException("BR-CN-02", "Thứ " + day.dayOfWeek() + " bị khai báo hai lần");
            }
        }
        if (seen.size() != DAYS_IN_WEEK) {
            throw new BusinessRuleViolationException("BR-CN-02", "Phải khai báo giờ mở cửa đủ 7 ngày trong tuần");
        }
        Map<Integer, List<TimeRange>> result = new TreeMap<>();
        for (DayHoursDto day : days) {
            result.put(day.dayOfWeek(), validRanges(day));
        }
        return new Version(effectiveFrom, result);
    }

    private static List<TimeRange> validRanges(DayHoursDto day) {
        if (day.ranges().size() > MAX_RANGES_PER_DAY) {
            throw new BusinessRuleViolationException("BR-CN-02",
                    "Thứ " + day.dayOfWeek() + " có quá " + MAX_RANGES_PER_DAY + " khoảng giờ mở cửa");
        }
        List<TimeRange> ranges = day.ranges().stream().map(r -> new TimeRange(r.open(), r.close()))
                .sorted(Comparator.comparing(TimeRange::open)).toList();
        LocalTime previousClose = null;
        for (TimeRange range : ranges) {
            if (!range.open().isBefore(range.close())) {
                throw new BusinessRuleViolationException("BR-CN-02",
                        "Thứ " + day.dayOfWeek() + ": giờ mở cửa phải trước giờ đóng cửa");
            }
            if (previousClose != null && range.open().isBefore(previousClose)) {
                throw new BusinessRuleViolationException("BR-CN-02",
                        "Thứ " + day.dayOfWeek() + ": các khoảng giờ mở cửa không được chồng nhau");
            }
            previousClose = range.close();
        }
        return ranges;
    }
}
