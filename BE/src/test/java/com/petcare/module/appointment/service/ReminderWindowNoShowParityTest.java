package com.petcare.module.appointment.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;

import com.petcare.platform.config.TimeConfig;

/**
 * Ma trận đồng thời C5 (docs/adr/0056): không có (khung, now) nào để một lịch vừa đến hạn nhắc ST03 vừa quá hạn NO_SHOW
 * ST05 — ST03 cần {@code giờ hẹn > now}, ST05 cần {@code giờ hẹn + noShowAfter < now}. Vét cạn quanh mọi biên (±2 µs,
 * ±1 phút) với mọi mốc no-show trong khoảng V2 (15–120 phút) và mọi H (1–72 giờ, cách 1 giờ), kể cả {@code noShowAfter = 0}.
 */
class ReminderWindowNoShowParityTest {

    private static final Instant BASE = Instant.parse("2026-10-19T16:59:00Z"); // 23:59 giờ Việt Nam, sát nửa đêm

    @Test
    void reminderAndNoShowWindowsNeverOverlap() {
        long[] offsetsMicros = {-120_000_000, -60_000_000, -2, -1, 0, 1, 2, 60_000_000, 120_000_000};
        int checked = 0;
        for (int noShowAfter : new int[] {0, 15, 30, 60, 120}) {
            for (int hours = 1; hours <= 72; hours++) {
                for (long nowOffset : offsetsMicros) {
                    Instant now = BASE.plus(nowOffset, ChronoUnit.MICROS);
                    ReminderWindow window = ReminderWindow.at(now, hours);
                    NoShowCutoff cutoff = NoShowCutoff.at(now, noShowAfter);
                    LocalDateTime local = LocalDateTime.ofInstant(now, TimeConfig.BUSINESS_ZONE);
                    for (LocalDateTime slot : candidateSlots(local, noShowAfter, hours)) {
                        LocalDate date = slot.toLocalDate();
                        LocalTime time = slot.toLocalTime();
                        boolean due = window.contains(date, time) && ReminderWindow.notStartedAt(now, date, time);
                        assertThat(due && cutoff.isOverdue(date, time))
                                .as("slot=%s now=%s noShow=%d H=%d", slot, local, noShowAfter, hours).isFalse();
                        checked++;
                    }
                }
            }
        }
        assertThat(checked).isGreaterThan(10_000);
    }

    private static LocalDateTime[] candidateSlots(LocalDateTime now, int noShowAfter, int hours) {
        LocalDateTime nowMicros = now.truncatedTo(ChronoUnit.MICROS);
        LocalDateTime noShowEdge = nowMicros.minusMinutes(noShowAfter);
        LocalDateTime upper = nowMicros.plusHours(hours);
        return new LocalDateTime[] {
                nowMicros.minusNanos(2_000), nowMicros.minusNanos(1_000), nowMicros, nowMicros.plusNanos(1_000),
                noShowEdge.minusNanos(1_000), noShowEdge, noShowEdge.plusNanos(1_000),
                upper.minusNanos(1_000), upper, upper.plusNanos(1_000),
                nowMicros.minusMinutes(1), nowMicros.plusMinutes(1)};
    }
}
