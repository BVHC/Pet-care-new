package com.petcare.module.appointment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;

import com.petcare.platform.config.TimeConfig;

/** {@link NoShowCutoff} (docs/adr/0053 §1): quá hạn nghiêm ngặt, đối ngẫu của {@link CheckInWindow}. */
class NoShowCutoffTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 19);

    private static Instant vn(LocalDate date, LocalTime time) {
        return ZonedDateTime.of(LocalDateTime.of(date, time), TimeConfig.BUSINESS_ZONE).toInstant();
    }

    @Test
    void exactlyNoShowAfterMinutesIsNotOverdue() {
        NoShowCutoff cutoff = NoShowCutoff.at(vn(DAY, LocalTime.of(9, 30)), 30);

        assertThat(cutoff.isOverdue(DAY, LocalTime.of(9, 0))).isFalse();
    }

    @Test
    void oneMicrosecondPastIsOverdue() {
        NoShowCutoff cutoff = NoShowCutoff.at(vn(DAY, LocalTime.of(9, 30, 0, 1_000)), 30);

        assertThat(cutoff.isOverdue(DAY, LocalTime.of(9, 0))).isTrue();
    }

    @Test
    void nanosecondsBelowMicrosecondDoNotMakeItOverdue() {
        NoShowCutoff cutoff = NoShowCutoff.at(vn(DAY, LocalTime.of(9, 30, 0, 999)), 30);

        assertThat(cutoff.time()).isEqualTo(LocalTime.of(9, 0));
        assertThat(cutoff.isOverdue(DAY, LocalTime.of(9, 0))).isFalse();
    }

    @Test
    void cutoffCrossesMidnightIntoPreviousDay() {
        NoShowCutoff cutoff = NoShowCutoff.at(vn(DAY, LocalTime.of(0, 10)), 30);

        assertThat(cutoff.date()).isEqualTo(DAY.minusDays(1));
        assertThat(cutoff.time()).isEqualTo(LocalTime.of(23, 40));
        assertThat(cutoff.isOverdue(DAY.minusDays(1), LocalTime.of(23, 30))).isTrue();
        assertThat(cutoff.isOverdue(DAY.minusDays(1), LocalTime.of(23, 40))).isFalse();
        assertThat(cutoff.isOverdue(DAY, LocalTime.of(0, 0))).isFalse();
    }

    @Test
    void earlierDaysAreAlwaysOverdueAndLaterDaysNever() {
        NoShowCutoff cutoff = NoShowCutoff.at(vn(DAY, LocalTime.of(8, 0)), 30);

        assertThat(cutoff.isOverdue(DAY.minusDays(1), LocalTime.of(23, 59))).isTrue();
        assertThat(cutoff.isOverdue(DAY.plusDays(1), LocalTime.of(0, 0))).isFalse();
    }

    @Test
    void invalidInputsRejected() {
        assertThatThrownBy(() -> NoShowCutoff.at(Instant.EPOCH, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NoShowCutoff.at(null, 30)).isInstanceOf(NullPointerException.class);
    }
}
