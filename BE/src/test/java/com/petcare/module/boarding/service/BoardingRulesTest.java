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
    void nightListFormat() {
        assertThat(BoardingRules.nightList(List.of(LocalDate.parse("2009-06-12"), LocalDate.parse("2009-06-13"))))
                .isEqualTo("12/06, 13/06");
    }
}
