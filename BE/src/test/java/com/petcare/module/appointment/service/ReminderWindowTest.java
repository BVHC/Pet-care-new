package com.petcare.module.appointment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;

/**
 * {@link ReminderWindow} (BR-LH-12, docs/adr/0056 Q4): {@code now < giờ hẹn ≤ now + H} theo giờ Việt Nam, biên ±1 µs ở
 * cả hai đầu, qua nửa đêm / cuối tháng / cuối năm, H = 72, chuỗi giờ cho native query.
 */
class ReminderWindowTest {

    /** 2026-10-19 09:00 giờ Việt Nam. */
    private static final Instant NOW = Instant.parse("2026-10-19T02:00:00Z");

    @Test
    void windowIsNowToNowPlusHoursInVietnamTime() {
        ReminderWindow window = ReminderWindow.at(NOW, 24);

        assertThat(window.from()).isEqualTo(LocalDateTime.of(2026, 10, 19, 9, 0));
        assertThat(window.to()).isEqualTo(LocalDateTime.of(2026, 10, 20, 9, 0));
        assertThat(window.fromDate()).isEqualTo(LocalDate.of(2026, 10, 19));
        assertThat(window.fromTime()).isEqualTo("09:00");
        assertThat(window.toDate()).isEqualTo(LocalDate.of(2026, 10, 20));
        assertThat(window.toTime()).isEqualTo("09:00");
    }

    @Test
    void lowerBoundIsExclusiveUpperBoundInclusive() {
        ReminderWindow window = ReminderWindow.at(NOW, 24);
        LocalDate today = LocalDate.of(2026, 10, 19);
        LocalDate tomorrow = LocalDate.of(2026, 10, 20);

        assertThat(window.contains(today, LocalTime.of(9, 0))).as("đang bắt đầu").isFalse();
        assertThat(window.contains(today, LocalTime.of(8, 30))).as("đã qua").isFalse();
        assertThat(window.contains(today, LocalTime.of(9, 30))).isTrue();
        assertThat(window.contains(tomorrow, LocalTime.of(9, 0))).as("đúng H giờ trước").isTrue();
        assertThat(window.contains(tomorrow, LocalTime.of(9, 30))).as("quá H giờ").isFalse();
    }

    @Test
    void boundariesAtOneMicrosecond() {
        Instant now = Instant.parse("2026-10-19T02:00:00.000001Z"); // 09:00:00.000001
        ReminderWindow window = ReminderWindow.at(now, 24);

        assertThat(window.fromTime()).isEqualTo("09:00:00.000001");
        assertThat(window.contains(LocalDate.of(2026, 10, 19), LocalTime.of(9, 0))).isFalse();
        assertThat(window.contains(LocalDate.of(2026, 10, 19), LocalTime.of(9, 0, 0, 2_000))).isTrue();
        assertThat(window.contains(LocalDate.of(2026, 10, 20), LocalTime.of(9, 0, 0, 1_000))).isTrue();
        assertThat(window.contains(LocalDate.of(2026, 10, 20), LocalTime.of(9, 0, 0, 2_000))).isFalse();
    }

    @Test
    void nanosecondsAreTruncatedLikeTheColumn() {
        ReminderWindow window = ReminderWindow.at(Instant.parse("2026-10-19T02:00:00.000001999Z"), 1);

        assertThat(window.from()).isEqualTo(LocalDateTime.of(2026, 10, 19, 9, 0, 0, 1_000));
    }

    @Test
    void crossesMidnightMonthAndYear() {
        ReminderWindow lateEvening = ReminderWindow.at(Instant.parse("2026-12-31T16:30:00Z"), 24); // 23:30 31/12
        assertThat(lateEvening.from()).isEqualTo(LocalDateTime.of(2026, 12, 31, 23, 30));
        assertThat(lateEvening.to()).isEqualTo(LocalDateTime.of(2027, 1, 1, 23, 30));
        assertThat(lateEvening.contains(LocalDate.of(2027, 1, 1), LocalTime.of(8, 0))).isTrue();
        assertThat(lateEvening.contains(LocalDate.of(2026, 12, 31), LocalTime.of(23, 0))).isFalse();

        ReminderWindow longest = ReminderWindow.at(Instant.parse("2026-10-30T17:00:00Z"), 72); // 00:00 31/10
        assertThat(longest.to()).isEqualTo(LocalDateTime.of(2026, 11, 3, 0, 0));
        assertThat(longest.contains(LocalDate.of(2026, 11, 2), LocalTime.of(23, 30))).isTrue();
        assertThat(longest.contains(LocalDate.of(2026, 11, 3), LocalTime.of(0, 0))).isTrue();
    }

    @Test
    void notStartedAtUsesFreshNow() {
        LocalDate day = LocalDate.of(2026, 10, 19);

        assertThat(ReminderWindow.notStartedAt(NOW, day, LocalTime.of(9, 0))).isFalse();
        assertThat(ReminderWindow.notStartedAt(NOW.minusNanos(1_000), day, LocalTime.of(9, 0))).isTrue();
        assertThat(ReminderWindow.notStartedAt(NOW, day, LocalTime.of(9, 30))).isTrue();
    }

    @Test
    void invalidArgumentsRejected() {
        assertThatThrownBy(() -> ReminderWindow.at(NOW, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReminderWindow.at(null, 24)).isInstanceOf(NullPointerException.class);
        LocalDateTime t = LocalDateTime.of(2026, 10, 19, 9, 0);
        assertThatThrownBy(() -> new ReminderWindow(t, t)).isInstanceOf(IllegalArgumentException.class);
    }
}
