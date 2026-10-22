package com.petcare.module.sales.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.petcare.module.branch.api.BranchQueryApi.TimeRange;

/** Hạn tự chốt ca thường (BR-TG-05; ST13; docs/adr/0064): giờ đóng của khoảng cuối trong ngày + grace [CFG]. */
class ShiftDeadlineTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 21);

    private static TimeRange range(String open, String close) {
        return new TimeRange(LocalTime.parse(open), LocalTime.parse(close));
    }

    @Test
    void oneRangePlusGraceOnTheSameDay() {
        assertThat(ShiftDeadline.regular(DAY, List.of(range("08:00", "18:00")), 30))
                .isEqualTo(LocalDateTime.of(2026, 10, 21, 18, 30));
    }

    @Test
    void lastRangeCountsNotTheLunchBreak() {
        assertThat(ShiftDeadline.regular(DAY, List.of(range("08:00", "12:00"), range("13:30", "20:00")), 30))
                .isEqualTo(LocalDateTime.of(2026, 10, 21, 20, 30));
    }

    @Test
    void latestCloseWinsEvenIfRangesComeInAnyOrder() {
        assertThat(ShiftDeadline.regular(DAY, List.of(range("13:30", "20:00"), range("08:00", "12:00")), 30))
                .isEqualTo(LocalDateTime.of(2026, 10, 21, 20, 30));
    }

    @Test
    void graceMayCrossMidnight() {
        assertThat(ShiftDeadline.regular(DAY, List.of(range("08:00", "23:50")), 30))
                .isEqualTo(LocalDateTime.of(2026, 10, 22, 0, 20));
    }

    @Test
    void zeroGraceIsExactlyTheClosingTime() {
        assertThat(ShiftDeadline.regular(DAY, List.of(range("08:00", "18:00")), 0))
                .isEqualTo(LocalDateTime.of(2026, 10, 21, 18, 0));
    }

    @Test
    void dayWithoutOpeningRangesClosesAtMidnightOfTheNextDay() {
        assertThat(ShiftDeadline.regular(DAY, List.of(), 30)).isEqualTo(LocalDateTime.of(2026, 10, 22, 0, 0));
    }
}
