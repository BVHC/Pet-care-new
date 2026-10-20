package com.petcare.module.visit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.petcare.module.visit.entity.PriorityClass;

/** {@link QueuePlacement}: biên BR-TN-03 (trễ tối đa [CFG] phút giữ ưu tiên, tính là được) và BR-TN-04. */
class QueuePlacementTest {

    private static final Instant SLOT = Instant.parse("2026-10-15T02:00:00Z");
    private static final int LATE = 15;

    @Test
    void earlyArrivalKeepsTheAppointmentPriorityAtTheSlotTime() {
        Instant now = SLOT.minus(Duration.ofMinutes(30));

        assertThat(QueuePlacement.of(false, SLOT, now, LATE))
                .isEqualTo(new QueuePlacement(PriorityClass.APPOINTMENT, SLOT));
    }

    @Test
    void exactlyTheLateLimitStillKeepsThePriority() {
        assertThat(QueuePlacement.of(false, SLOT, SLOT.plus(Duration.ofMinutes(LATE)), LATE))
                .isEqualTo(new QueuePlacement(PriorityClass.APPOINTMENT, SLOT));
    }

    @Test
    void oneMicrosecondPastTheLimitIsQueuedAsWalkInAtCheckInTime() {
        Instant now = SLOT.plus(Duration.ofMinutes(LATE)).plusNanos(1_000);

        assertThat(QueuePlacement.of(false, SLOT, now, LATE)).isEqualTo(new QueuePlacement(PriorityClass.WALK_IN, now));
    }

    @Test
    void zeroLateMinutesMeansOnTimeOnly() {
        assertThat(QueuePlacement.of(false, SLOT, SLOT, 0).priorityClass()).isEqualTo(PriorityClass.APPOINTMENT);
        assertThat(QueuePlacement.of(false, SLOT, SLOT.plusNanos(1_000), 0).priorityClass())
                .isEqualTo(PriorityClass.WALK_IN);
    }

    @Test
    void walkInIsQueuedAtCheckInTime() {
        Instant now = SLOT.plusSeconds(7);

        assertThat(QueuePlacement.of(false, null, now, LATE)).isEqualTo(new QueuePlacement(PriorityClass.WALK_IN, now));
    }

    @Test
    void emergencyWinsWithOrWithoutAnAppointment() {
        Instant now = SLOT.minus(Duration.ofMinutes(10));

        assertThat(QueuePlacement.of(true, SLOT, now, LATE)).isEqualTo(new QueuePlacement(PriorityClass.EMERGENCY, now));
        assertThat(QueuePlacement.of(true, null, now, LATE)).isEqualTo(new QueuePlacement(PriorityClass.EMERGENCY, now));
    }

    @Test
    void negativeMinutesAreAProgrammingError() {
        assertThatThrownBy(() -> QueuePlacement.of(false, SLOT, SLOT, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
