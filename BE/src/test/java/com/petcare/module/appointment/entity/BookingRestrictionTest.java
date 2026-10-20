package com.petcare.module.appointment.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/** {@link BookingRestriction#lift} (docs/adr/0055): ghi ba cột gỡ sớm, giữ khoảng hiệu lực; gỡ lần hai là lỗi lập trình. */
class BookingRestrictionTest {

    private static final Instant STARTS = Instant.parse("2026-10-15T02:00:00Z");
    private static final Instant ENDS = Instant.parse("2026-11-14T02:00:00Z");
    private static final Instant LIFTED = Instant.parse("2026-10-20T03:00:00Z");

    @Test
    void liftRecordsWhenWhoAndWhyAndKeepsTheWindow() {
        BookingRestriction restriction = BookingRestriction.start(5L, STARTS, ENDS, 3);

        restriction.lift(LIFTED, 9L, "Khách vắng có lý do");

        assertThat(restriction.getLiftedAt()).isEqualTo(LIFTED);
        assertThat(restriction.getLiftedBy()).isEqualTo(9L);
        assertThat(restriction.getLiftReason()).isEqualTo("Khách vắng có lý do");
        assertThat(restriction.getStartsAt()).isEqualTo(STARTS);
        assertThat(restriction.getEndsAt()).isEqualTo(ENDS);
        assertThat(restriction.getViolationCount()).isEqualTo((short) 3);
    }

    @Test
    void liftingTwiceIsAProgrammingError() {
        BookingRestriction restriction = BookingRestriction.start(5L, STARTS, ENDS, 3);
        restriction.lift(LIFTED, 9L, "Lần đầu");

        assertThatThrownBy(() -> restriction.lift(LIFTED.plusSeconds(1), 10L, "Lần hai"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(restriction.getLiftedBy()).isEqualTo(9L);
        assertThat(restriction.getLiftReason()).isEqualTo("Lần đầu");
    }
}
