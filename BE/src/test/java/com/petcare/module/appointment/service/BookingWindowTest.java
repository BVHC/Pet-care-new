package com.petcare.module.appointment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;

/**
 * BR-LH-04 (docs/adr/0046 Q1): khách — mốc gần nhất theo thời điểm (≥ now + giờ [CFG], đúng mốc vẫn được), mốc xa nhất
 * theo ngày (≤ hôm nay + ngày [CFG], giờ Việt Nam); lễ tân — khung chưa bắt đầu (> now). Ngày tính theo giờ Việt Nam
 * dù {@code now} là UTC.
 */
class BookingWindowTest {

    /** 2026-10-15 09:00 giờ Việt Nam. */
    private static final Instant NOW = Instant.parse("2026-10-15T02:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);

    private final BookingWindow customer = BookingWindow.forCustomer(NOW, 24, 30);
    private final BookingWindow staff = BookingWindow.forStaff(NOW);

    @Test
    void customerLeadIsInclusiveAtExactlyTheConfiguredHours() {
        LocalDate tomorrow = TODAY.plusDays(1);
        assertThat(customer.allows(tomorrow, LocalTime.of(9, 0))).as("đúng 24 giờ").isTrue();
        assertThat(customer.allows(tomorrow, LocalTime.of(8, 59))).as("thiếu 1 phút").isFalse();
        assertThat(customer.allows(tomorrow, LocalTime.of(8, 30))).isFalse();
        assertThat(customer.allows(tomorrow, LocalTime.of(9, 30))).isTrue();
        assertThat(customer.allows(TODAY, LocalTime.of(23, 30))).as("hôm nay luôn < 24 giờ").isFalse();
    }

    @Test
    void customerFarLimitIsByDateSoTheLastDayHasEverySlot() {
        LocalDate last = TODAY.plusDays(30);
        assertThat(customer.mayContain(last)).isTrue();
        assertThat(customer.allows(last, LocalTime.of(0, 0))).isTrue();
        assertThat(customer.allows(last, LocalTime.of(23, 30))).as("cả khung sau giờ hiện tại của ngày thứ 30")
                .isTrue();
        assertThat(customer.mayContain(last.plusDays(1))).isFalse();
        assertThat(customer.allows(last.plusDays(1), LocalTime.of(9, 0))).isFalse();
    }

    @Test
    void pastDatesAreNeverBookable() {
        assertThat(customer.mayContain(TODAY.minusDays(1))).isFalse();
        assertThat(staff.mayContain(TODAY.minusDays(1))).isFalse();
        assertThat(staff.allows(TODAY.minusDays(1), LocalTime.of(23, 30))).isFalse();
    }

    @Test
    void staffSeesOnlySlotsNotYetStarted() {
        assertThat(staff.mayContain(TODAY)).isTrue();
        assertThat(staff.allows(TODAY, LocalTime.of(9, 0))).as("đúng giờ bắt đầu là đã bắt đầu").isFalse();
        assertThat(staff.allows(TODAY, LocalTime.of(8, 30))).isFalse();
        assertThat(staff.allows(TODAY, LocalTime.of(9, 30))).isTrue();
        assertThat(BookingWindow.forStaff(NOW.minusSeconds(1)).allows(TODAY, LocalTime.of(9, 0))).isTrue();
    }

    @Test
    void staffHasNoFarLimit() {
        assertThat(staff.mayContain(TODAY.plusYears(5))).isTrue();
        assertThat(staff.allows(TODAY.plusDays(400), LocalTime.of(8, 0))).isTrue();
    }

    @Test
    void configuredValuesAreHonoured() {
        BookingWindow window = BookingWindow.forCustomer(NOW, 0, 7);
        assertThat(window.allows(TODAY, LocalTime.of(9, 0))).as("lead 0: đúng bây giờ vẫn được").isTrue();
        assertThat(window.allows(TODAY, LocalTime.of(8, 30))).isFalse();
        assertThat(window.mayContain(TODAY.plusDays(7))).isTrue();
        assertThat(window.mayContain(TODAY.plusDays(8))).isFalse();

        BookingWindow longLead = BookingWindow.forCustomer(NOW, 72, 30);
        assertThat(longLead.allows(TODAY.plusDays(3), LocalTime.of(9, 0))).isTrue();
        assertThat(longLead.allows(TODAY.plusDays(3), LocalTime.of(8, 30))).isFalse();
    }

    @Test
    void businessDateComesFromVietnamTimeNotUtc() {
        Instant lateUtc = Instant.parse("2026-10-15T17:30:00Z"); // 00:30 ngày 16/10 giờ Việt Nam
        BookingWindow window = BookingWindow.forStaff(lateUtc);
        assertThat(window.mayContain(LocalDate.of(2026, 10, 15))).as("15/10 đã là hôm qua ở Việt Nam").isFalse();
        assertThat(window.allows(LocalDate.of(2026, 10, 16), LocalTime.of(1, 0))).isTrue();
        assertThat(window.allows(LocalDate.of(2026, 10, 16), LocalTime.of(0, 0))).isFalse();

        BookingWindow customerLate = BookingWindow.forCustomer(lateUtc, 24, 30);
        assertThat(customerLate.mayContain(LocalDate.of(2026, 11, 15))).as("16/10 + 30").isTrue();
        assertThat(customerLate.mayContain(LocalDate.of(2026, 11, 16))).isFalse();
    }

    @Test
    void rejectsNegativeParameters() {
        assertThatThrownBy(() -> BookingWindow.forCustomer(NOW, -1, 30)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BookingWindow.forCustomer(NOW, 24, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
