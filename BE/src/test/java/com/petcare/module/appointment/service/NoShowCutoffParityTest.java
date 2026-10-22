package com.petcare.module.appointment.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;

import com.petcare.platform.config.TimeConfig;

/**
 * Chứng minh ST05 không bao giờ chuyển NO_SHOW một lịch còn check-in được (docs/adr/0053, G1): với mọi {@code now} quanh
 * các biên, một khung không thể vừa {@link CheckInWindow#contains} vừa {@link NoShowCutoff#isOverdue}.
 */
class NoShowCutoffParityTest {

    private static final int EARLY = 30;
    private static final int NO_SHOW_AFTER = 30;

    @Test
    void slotNeverBothCheckInReadyAndOverdue() {
        LocalDate day = LocalDate.of(2026, 10, 19);
        long[] offsetsSeconds = {-61, -60, -1, 0, 1, 59, 60, 61};
        int checked = 0;
        for (int i = 0; i <= 48; i++) {
            LocalDateTime slot = day.atStartOfDay().plusMinutes(i * 30L);
            for (int noShow : new int[] {15, 30, 120}) {
                for (long off : offsetsSeconds) {
                    for (long shift : new long[] {-noShow * 60L, 0, EARLY * 60L}) {
                        Instant now = ZonedDateTime.of(slot, TimeConfig.BUSINESS_ZONE).toInstant()
                                .plusSeconds(shift + off).plusNanos(off > 0 ? 1_000 : 0);
                        CheckInWindow window = CheckInWindow.at(now, EARLY, noShow);
                        NoShowCutoff cutoff = NoShowCutoff.at(now, noShow);
                        LocalDate d = slot.toLocalDate();
                        LocalTime t = slot.toLocalTime();
                        assertThat(window.contains(d, t) && cutoff.isOverdue(d, t))
                                .as("slot=%s now=%s noShow=%d", slot, now, noShow).isFalse();
                        checked++;
                    }
                }
            }
        }
        assertThat(checked).isGreaterThan(1000);
    }

    @Test
    void pastTheWindowIsOverdueOnlyAfterLatestCheckInMoment() {
        LocalDateTime slot = LocalDateTime.of(2026, 10, 19, 9, 0);
        Instant latestCheckIn = ZonedDateTime.of(slot.plusMinutes(NO_SHOW_AFTER), TimeConfig.BUSINESS_ZONE).toInstant();

        assertThat(CheckInWindow.at(latestCheckIn, EARLY, NO_SHOW_AFTER).contains(slot.toLocalDate(), slot.toLocalTime()))
                .isTrue();
        assertThat(NoShowCutoff.at(latestCheckIn, NO_SHOW_AFTER).isOverdue(slot.toLocalDate(), slot.toLocalTime()))
                .isFalse();

        Instant after = latestCheckIn.plusNanos(1_000);
        assertThat(NoShowCutoff.at(after, NO_SHOW_AFTER).isOverdue(slot.toLocalDate(), slot.toLocalTime())).isTrue();
        assertThat(CheckInWindow.at(after, EARLY, NO_SHOW_AFTER).contains(slot.toLocalDate(), slot.toLocalTime()))
                .isFalse();
    }
}
