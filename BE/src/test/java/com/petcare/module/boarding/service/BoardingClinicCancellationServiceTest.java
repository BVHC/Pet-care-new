package com.petcare.module.boarding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.boarding.entity.BoardingBooking;
import com.petcare.module.boarding.entity.BoardingBookingStatus;
import com.petcare.module.boarding.entity.BookingChannel;
import com.petcare.module.boarding.fsm.BoardingBookingTransitionHandler;
import com.petcare.module.boarding.repository.BoardingBookingRepository;

class BoardingClinicCancellationServiceTest {

    private BoardingBookingRepository bookings;
    private BoardingClinicCancellationNotices notices;
    private BoardingClinicCancellationService service;

    @BeforeEach
    void setUp() {
        bookings = mock(BoardingBookingRepository.class);
        notices = mock(BoardingClinicCancellationNotices.class);
        when(bookings.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        service = new BoardingClinicCancellationService(bookings, new BoardingBookingTransitionHandler(), notices);
    }

    private static BoardingBooking booking(long id, BoardingBookingStatus status) {
        BoardingBooking b = BoardingBooking.book("LT-" + id, 1L, 2L, 3L, 4L, LocalDate.parse("2009-06-12"),
                LocalDate.parse("2009-06-14"), 150_000L, BookingChannel.COUNTER, 9L);
        ReflectionTestUtils.setField(b, "id", id);
        ReflectionTestUtils.setField(b, "status", status);
        return b;
    }

    @Test
    void skipsRowsThatLeftBookedAndNotifiesOnlyTheCancelledOnes() {
        BoardingBooking stillBooked = booking(1, BoardingBookingStatus.BOOKED);
        BoardingBooking noShow = booking(2, BoardingBookingStatus.NO_SHOW);
        when(bookings.findAllByIdInForUpdate(List.of(1L, 2L))).thenReturn(List.of(stillBooked, noShow));

        List<Long> cancelled = service.cancelByClinic(List.of(2L, 1L, 1L), BoardingClinicCause.HOLIDAY_ADDED);

        assertThat(cancelled).containsExactly(1L);
        assertThat(stillBooked.getStatus()).isEqualTo(BoardingBookingStatus.CANCELLED);
        assertThat(noShow.getStatus()).isEqualTo(BoardingBookingStatus.NO_SHOW);
        ArgumentCaptor<List<BoardingBooking>> captor = ArgumentCaptor.forClass(List.class);
        verify(notices).enqueueFor(captor.capture(), eq(BoardingClinicCause.HOLIDAY_ADDED));
        assertThat(captor.getValue()).containsExactly(stillBooked);
    }

    @Test
    void missingIdIsIllegalStateAndNothingIsWritten() {
        when(bookings.findAllByIdInForUpdate(List.of(1L, 2L)))
                .thenReturn(List.of(booking(1, BoardingBookingStatus.BOOKED)));

        assertThatThrownBy(() -> service.cancelByClinic(List.of(1L, 2L), BoardingClinicCause.PET_DECEASED))
                .isInstanceOf(IllegalStateException.class);

        verify(bookings, never()).saveAndFlush(any());
        verify(notices, never()).enqueueFor(anyList(), any());
    }

    @Test
    void locksInAscendingBatchesOf100() {
        List<Long> ids = new ArrayList<>(LongStream.rangeClosed(1, 250).boxed().toList());
        java.util.Collections.reverse(ids);
        when(bookings.findAllByIdInForUpdate(any())).thenAnswer(inv -> ((List<Long>) inv.getArgument(0)).stream()
                .map(id -> booking(id, BoardingBookingStatus.CANCELLED)).toList());

        service.cancelByClinic(ids, BoardingClinicCause.PET_DECEASED);

        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(bookings, times(3)).findAllByIdInForUpdate(captor.capture());
        assertThat(captor.getAllValues()).extracting(List::size).containsExactly(100, 100, 50);
        assertThat(captor.getAllValues().get(0)).startsWith(1L).endsWith(100L);
        assertThat(captor.getAllValues().get(2)).startsWith(201L).endsWith(250L);
    }

    @Test
    void emptyInputTouchesNothing() {
        assertThat(service.cancelByClinic(List.of(), BoardingClinicCause.PET_DECEASED)).isEmpty();

        verify(bookings, never()).findAllByIdInForUpdate(any());
    }
}
