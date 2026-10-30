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

    @Test
    void checkInOnTheDayKeepsCheckInDate() {
        BoardingBooking b = booked();
        java.time.Instant at = java.time.Instant.parse("2009-06-12T03:00:00Z");

        b.checkIn(7L, at, LocalDate.parse("2009-06-12"));

        assertThat(b.getStatus()).isEqualTo(BoardingBookingStatus.CHECKED_IN);
        assertThat(b.getKennelId()).isEqualTo(7L);
        assertThat(b.getActualCheckInAt()).isEqualTo(at);
        assertThat(b.getCheckInDate()).isEqualTo(LocalDate.parse("2009-06-12"));
    }

    @Test
    void earlyCheckInMovesCheckInDateToToday() {
        BoardingBooking b = booked();

        b.checkIn(7L, java.time.Instant.parse("2009-06-10T03:00:00Z"), LocalDate.parse("2009-06-10"));

        assertThat(b.getCheckInDate()).isEqualTo(LocalDate.parse("2009-06-10"));
        assertThat(b.getCheckOutDate()).isEqualTo(LocalDate.parse("2009-06-14"));
    }

    @Test
    void checkOutRecordsReasonNoteAndTime() {
        BoardingBooking b = booked();
        b.checkIn(7L, java.time.Instant.parse("2009-06-12T03:00:00Z"), LocalDate.parse("2009-06-12"));
        java.time.Instant at = java.time.Instant.parse("2009-06-14T05:00:00Z");

        b.checkOut(BookingEndReason.PET_DECEASED, "Mat luc 9h", at);

        assertThat(b.getStatus()).isEqualTo(BoardingBookingStatus.CHECKED_OUT);
        assertThat(b.getEndReason()).isEqualTo(BookingEndReason.PET_DECEASED);
        assertThat(b.getEndNote()).isEqualTo("Mat luc 9h");
        assertThat(b.getActualCheckOutAt()).isEqualTo(at);
    }

    @Test
    void markOverdueAndNoticeFlags() {
        BoardingBooking b = booked();
        b.checkIn(7L, java.time.Instant.parse("2009-06-12T03:00:00Z"), LocalDate.parse("2009-06-12"));
        java.time.Instant since = java.time.Instant.parse("2009-06-14T05:00:00Z");

        b.markOverdue(since);
        b.overdueNoticeSent(LocalDate.parse("2009-06-14"));
        b.managerNotified(since.plusSeconds(60));

        assertThat(b.getStatus()).isEqualTo(BoardingBookingStatus.OVERDUE);
        assertThat(b.getOverdueSince()).isEqualTo(since);
        assertThat(b.getLastOverdueNoticeOn()).isEqualTo(LocalDate.parse("2009-06-14"));
        assertThat(b.getManagerNotifiedAt()).isEqualTo(since.plusSeconds(60));
    }
}
