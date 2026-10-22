package com.petcare.module.boarding.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class BoardingBookingTest {

    private static BoardingBooking booked() {
        return BoardingBooking.book("LT-0906-00001", 1L, 2L, 3L, 4L, LocalDate.parse("2009-06-12"),
                LocalDate.parse("2009-06-14"), 150_000L, BookingChannel.COUNTER, 9L);
    }

    @Test
    void bookStartsBookedWithSnapshotPriceAndNoCancelData() {
        BoardingBooking b = booked();

        assertThat(b.getStatus()).isEqualTo(BoardingBookingStatus.BOOKED);
        assertThat(b.getNightlyPrice()).isEqualTo(150_000L);
        assertThat(b.isLateCancel()).isFalse();
        assertThat(b.getCancelSource()).isNull();
        assertThat(b.getKennelId()).isNull();
    }

    @Test
    void cancelRecordsSourceReasonAndLateFlag() {
        BoardingBooking b = booked();

        b.cancel(BookingCancelSource.STAFF, "x", true);

        assertThat(b.getStatus()).isEqualTo(BoardingBookingStatus.CANCELLED);
        assertThat(b.getCancelSource()).isEqualTo(BookingCancelSource.STAFF);
        assertThat(b.getCancelReason()).isEqualTo("x");
        assertThat(b.isLateCancel()).isTrue();
    }

    @Test
    void markNoShowSetsStatus() {
        BoardingBooking b = booked();

        b.markNoShow();

        assertThat(b.getStatus()).isEqualTo(BoardingBookingStatus.NO_SHOW);
    }

    @Test
    void extendMovesCheckOutDate() {
        BoardingBooking b = booked();

        b.extendTo(LocalDate.parse("2009-06-16"));

        assertThat(b.getCheckOutDate()).isEqualTo(LocalDate.parse("2009-06-16"));
    }
}
