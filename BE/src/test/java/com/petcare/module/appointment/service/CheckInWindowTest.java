package com.petcare.module.appointment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;

/**
 * {@link CheckInWindow} (docs/adr/0048 §6): BR-TN-02 sớm nhất {@code early} phút trước giờ hẹn, BR-TN-03 / BR-LH-08 muộn
 * nhất {@code noShowAfter} phút sau giờ hẹn, hai biên tính là được; chỉ lịch của hôm nay (giờ Việt Nam); mốc tràn qua nửa
 * đêm không làm vòng giờ.
 */
class CheckInWindowTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);
    private static final LocalTime NINE = LocalTime.of(9, 0);

    /** {@code hh:mm:ss} giờ Việt Nam ngày 2026-10-15. */
    private static Instant vn(int hour, int minute, int second) {
        return TODAY.atTime(hour, minute, second).atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toInstant();
    }

    @Test
    void earliestCheckInIsExactlyEarlyMinutesBeforeTheSlot() {
        assertThat(CheckInWindow.at(vn(8, 30, 0), 30, 30).contains(TODAY, NINE)).isTrue();
        assertThat(CheckInWindow.at(vn(8, 29, 59), 30, 30).contains(TODAY, NINE)).isFalse();
    }

    @Test
    void latestCheckInIsExactlyNoShowMinutesAfterTheSlot() {
        assertThat(CheckInWindow.at(vn(9, 30, 0), 30, 30).contains(TODAY, NINE)).isTrue();
        assertThat(CheckInWindow.at(vn(9, 30, 1), 30, 30).contains(TODAY, NINE)).isFalse();
    }

    @Test
    void windowFollowsTheConfiguredMinutes() {
        CheckInWindow window = CheckInWindow.at(vn(9, 0, 0), 10, 15);

        assertThat(window).isEqualTo(new CheckInWindow(TODAY, LocalTime.of(8, 45), LocalTime.of(9, 10)));
        assertThat(window.contains(TODAY, LocalTime.of(9, 10))).isTrue();
        assertThat(window.contains(TODAY, LocalTime.of(9, 30))).isFalse();
        assertThat(window.contains(TODAY, LocalTime.of(8, 30))).isFalse();
        assertThat(CheckInWindow.at(vn(9, 0, 0), 0, 0).contains(TODAY, NINE)).isTrue();
    }

    @Test
    void onlyTodayCounts() {
        CheckInWindow window = CheckInWindow.at(vn(8, 45, 0), 30, 30);

        assertThat(window.contains(TODAY.plusDays(1), NINE)).isFalse();
        assertThat(window.contains(TODAY.minusDays(1), NINE)).isFalse();
    }

    @Test
    void lowerBoundBeforeMidnightIsClampedToStartOfDay() {
        CheckInWindow window = CheckInWindow.at(vn(0, 10, 0), 30, 30);

        assertThat(window).isEqualTo(new CheckInWindow(TODAY, LocalTime.MIN, LocalTime.of(0, 40)));
        assertThat(window.contains(TODAY, LocalTime.of(0, 0))).isTrue();
    }

    @Test
    void upperBoundAfterMidnightMeansNoUpperBoundNotAWrappedTime() {
        CheckInWindow window = CheckInWindow.at(vn(23, 50, 0), 30, 30);

        assertThat(window).isEqualTo(new CheckInWindow(TODAY, LocalTime.of(23, 20), null));
        assertThat(window.contains(TODAY, LocalTime.of(23, 30))).isTrue();
        assertThat(window.contains(TODAY, LocalTime.of(0, 0))).as("không vòng về 00:20").isFalse();
        assertThat(window.contains(TODAY.plusDays(1), LocalTime.of(0, 0))).as("BR-TN-02: đúng ngày").isFalse();
    }

    @Test
    void dateIsTheVietnameseDateNotUtc() {
        // 2026-10-14T17:30Z = 2026-10-15 00:30 giờ Việt Nam.
        assertThat(CheckInWindow.at(Instant.parse("2026-10-14T17:30:00Z"), 30, 30).date()).isEqualTo(TODAY);
    }

    @Test
    void negativeMinutesAreAProgrammingError() {
        assertThatThrownBy(() -> CheckInWindow.at(vn(9, 0, 0), -1, 30)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CheckInWindow.at(vn(9, 0, 0), 30, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
