package com.petcare.module.boarding.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.petcare.module.branch.api.BranchQueryApi.TimeRange;

class BoardingRulesTest {

    private static final LocalDate TODAY = LocalDate.parse("2009-06-10");

    @Test
    void customerWindowBounds() {
        assertThat(BoardingRules.customerWindowAllows(TODAY, LocalDate.parse("2009-06-10"), 1, 60)).isFalse();
        assertThat(BoardingRules.customerWindowAllows(TODAY, LocalDate.parse("2009-06-11"), 1, 60)).isTrue();
        assertThat(BoardingRules.customerWindowAllows(TODAY, LocalDate.parse("2009-08-09"), 1, 60)).isTrue();
        assertThat(BoardingRules.customerWindowAllows(TODAY, LocalDate.parse("2009-08-10"), 1, 60)).isFalse();
    }

    @Test
    void lateCancelFromIsMidnightMinusHours() {
        assertThat(BoardingRules.lateCancelFrom(LocalDate.parse("2009-06-12"), 24))
                .isEqualTo(Instant.parse("2009-06-10T17:00:00Z"));
    }

    @Test
    void closingTimeIsTheLatestClose() {
        List<TimeRange> ranges = List.of(new TimeRange(LocalTime.of(8, 0), LocalTime.of(12, 0)),
                new TimeRange(LocalTime.of(13, 30), LocalTime.of(19, 0)));

        assertThat(BoardingRules.closingTime(ranges)).contains(LocalTime.of(19, 0));
        assertThat(BoardingRules.closingTime(List.of())).isEqualTo(Optional.empty());
    }

    @Test
    void noShowCutoffIsLatestCloseOrNextMidnight() {
        List<TimeRange> ranges = List.of(new TimeRange(LocalTime.of(8, 0), LocalTime.of(12, 0)),
                new TimeRange(LocalTime.of(13, 30), LocalTime.of(19, 0)));

        assertThat(BoardingRules.noShowCutoff(LocalDate.parse("2009-06-12"), ranges))
                .isEqualTo(Instant.parse("2009-06-12T12:00:00Z"));
        assertThat(BoardingRules.noShowCutoff(LocalDate.parse("2009-06-12"), List.of()))
                .isEqualTo(Instant.parse("2009-06-12T17:00:00Z"));
    }

    @Test
    void withinOpeningHoursIsOpenInclusiveCloseExclusiveInVietnamTime() {
        List<TimeRange> ranges = List.of(new TimeRange(LocalTime.of(8, 0), LocalTime.of(12, 0)),
                new TimeRange(LocalTime.of(13, 30), LocalTime.of(19, 0)));

        assertThat(BoardingRules.withinOpeningHours(Instant.parse("2009-06-10T01:00:00Z"), ranges)).isTrue();   // 08:00
        assertThat(BoardingRules.withinOpeningHours(Instant.parse("2009-06-10T00:59:59Z"), ranges)).isFalse();  // 07:59
        assertThat(BoardingRules.withinOpeningHours(Instant.parse("2009-06-10T05:00:00Z"), ranges)).isFalse();  // 12:00
        assertThat(BoardingRules.withinOpeningHours(Instant.parse("2009-06-10T06:00:00Z"), ranges)).isFalse();  // 13:00
        assertThat(BoardingRules.withinOpeningHours(Instant.parse("2009-06-10T11:59:59Z"), ranges)).isTrue();   // 18:59
        assertThat(BoardingRules.withinOpeningHours(Instant.parse("2009-06-10T03:00:00Z"), List.of())).isFalse();
    }

    @Test
    void nightListFormat() {
        assertThat(BoardingRules.nightList(List.of(LocalDate.parse("2009-06-12"), LocalDate.parse("2009-06-13"))))
                .isEqualTo("12/06, 13/06");
    }

    @Test
    void chargedNightsTable() {
        assertThat(BoardingRules.chargedNights(d("2009-06-10"), d("2009-06-10"), false)).isEqualTo(1);
        assertThat(BoardingRules.chargedNights(d("2009-06-10"), d("2009-06-10"), true)).isEqualTo(1);
        assertThat(BoardingRules.chargedNights(d("2009-06-10"), d("2009-06-12"), false)).isEqualTo(2);
        assertThat(BoardingRules.chargedNights(d("2009-06-10"), d("2009-06-12"), true)).isEqualTo(3);
        assertThat(BoardingRules.chargedNights(d("2009-06-10"), d("2009-06-15"), false)).isEqualTo(5);
    }

    @Test
    void afterCheckoutTimeIsStrictlyAfterNoonVietnamTime() {
        LocalTime noon = LocalTime.of(12, 0);

        assertThat(BoardingRules.afterCheckoutTime(Instant.parse("2009-06-12T05:00:00Z"), noon)).isFalse();
        assertThat(BoardingRules.afterCheckoutTime(Instant.parse("2009-06-12T05:00:00.000001Z"), noon)).isTrue();
        assertThat(BoardingRules.afterCheckoutTime(Instant.parse("2009-06-12T04:59:59Z"), noon)).isFalse();
    }

    private static LocalDate d(String date) {
        return LocalDate.parse(date);
    }
}
