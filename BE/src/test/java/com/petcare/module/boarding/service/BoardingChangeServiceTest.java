package com.petcare.module.boarding.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.appointment.api.BookingRestrictionApi;
import com.petcare.module.boarding.dto.CancelBookingRequest;
import com.petcare.module.boarding.dto.ExtendBookingRequest;
import com.petcare.module.boarding.entity.BoardingBooking;
import com.petcare.module.boarding.entity.BookingChannel;
import com.petcare.module.boarding.fsm.BoardingBookingTransitionHandler;
import com.petcare.module.boarding.repository.BoardingBookingRepository;
import com.petcare.module.boarding.repository.BoardingBookingRepository.BookingKeys;
import com.petcare.module.branch.api.BranchApi;
import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.branch.api.BranchQueryApi.BranchStatus;
import com.petcare.module.branch.api.BranchQueryApi.TimeRange;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.customer.api.PetQueryApi.PetSummary;
import com.petcare.module.customer.api.Species;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

class BoardingChangeServiceTest {

    private static final long ID = 88L;
    private static final long BRANCH = 7L;
    private static final long PET = 21L;
    private static final long CUSTOMER = 14L;

    private PetQueryApi pets;
    private BranchApi branchLocks;
    private BookingRestrictionApi restrictions;
    private BoardingCapacity capacity;
    private BoardingBookingRepository bookings;
    private BoardingChangeService service;
    private BoardingBooking booking;

    @BeforeEach
    void setUp() {
        BranchScope scope = mock(BranchScope.class);
        SecurityPrincipal principal = mock(SecurityPrincipal.class);
        when(principal.role()).thenReturn("RECEPTIONIST");
        when(scope.current()).thenReturn(principal);
        BookingKeys keys = mock(BookingKeys.class);
        when(keys.getBranchId()).thenReturn(BRANCH);
        when(keys.getPetId()).thenReturn(PET);
        when(keys.getCustomerId()).thenReturn(CUSTOMER);
        bookings = mock(BoardingBookingRepository.class);
        when(bookings.findKeys(ID)).thenReturn(Optional.of(keys));
        booking = BoardingBooking.book("LT-0906-00001", CUSTOMER, PET, BRANCH, 5L, LocalDate.parse("2009-06-12"),
                LocalDate.parse("2009-06-14"), 150_000L, BookingChannel.COUNTER, 9L);
        ReflectionTestUtils.setField(booking, "id", ID);
        when(bookings.findByIdForUpdate(ID)).thenReturn(Optional.of(booking));
        when(bookings.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(bookings.findUpdatedAt(ID)).thenReturn(Instant.parse("2009-06-10T03:00:00.000001Z"));
        pets = mock(PetQueryApi.class);
        when(pets.findPetForShare(PET)).thenReturn(Optional.of(
                new PetSummary(PET, CUSTOMER, "Milu", Species.DOG, null, null, null)));
        branchLocks = mock(BranchApi.class);
        when(branchLocks.lockForBooking(BRANCH)).thenReturn(Optional.of(BranchStatus.ACTIVE));
        BranchQueryApi branches = mock(BranchQueryApi.class);
        when(branches.openingRangesOn(anyLong(), any())).thenReturn(
                List.of(new TimeRange(java.time.LocalTime.of(8, 0), java.time.LocalTime.of(19, 0))));
        SystemConfigApi configs = mock(SystemConfigApi.class);
        when(configs.getInt(ConfigKey.BOARDING_MIN_NIGHTS)).thenReturn(1);
        when(configs.getInt(ConfigKey.BOARDING_MAX_NIGHTS)).thenReturn(30);
        when(configs.getInt(ConfigKey.BOARDING_LATE_CANCEL_HOURS)).thenReturn(24);
        capacity = mock(BoardingCapacity.class);
        when(capacity.shortNights(anyLong(), anyLong(), any(), any(), any())).thenReturn(List.of());
        restrictions = mock(BookingRestrictionApi.class);
        Clock clock = Clock.fixed(Instant.parse("2009-06-10T03:00:00Z"), ZoneOffset.UTC);
        service = new BoardingChangeService(scope, mock(CustomerQueryApi.class), pets, branchLocks, branches,
                restrictions, configs, capacity,
                new BoardingAvailabilityService(scope, branches, null, capacity, configs, clock), bookings,
                new BoardingBookingTransitionHandler(), mock(BoardingAssembler.class), clock);
    }

    @Test
    void extendLocksPetThenBranchThenBookingThenChecksCapacityThenWrites() {
        service.extendBooking(ID, new ExtendBookingRequest(LocalDate.parse("2009-06-16")));

        InOrder order = inOrder(pets, branchLocks, bookings, capacity);
        order.verify(pets).findPetForShare(PET);
        order.verify(branchLocks).lockForBooking(BRANCH);
        order.verify(bookings).findByIdForUpdate(ID);
        order.verify(capacity).shortNights(anyLong(), anyLong(), any(), any(), any());
        order.verify(bookings).saveAndFlush(booking);
    }

    @Test
    void extendOfOverdueBookingIs409AndWritesNothing() {
        ReflectionTestUtils.setField(booking, "status",
                com.petcare.module.boarding.entity.BoardingBookingStatus.OVERDUE);

        assertThatThrownBy(() -> service.extendBooking(ID, new ExtendBookingRequest(LocalDate.parse("2009-06-16"))))
                .isInstanceOf(com.petcare.module.boarding.exception.BoardingConflictException.class);

        verify(bookings, never()).saveAndFlush(any());
    }

    @Test
    void lateCancelEvaluatesRestrictionAfterFlushWithTheStoredUpdatedAt() {
        // ngày nhận 11/06: mốc hủy muộn = 00:00 giờ VN 11/06 trừ 24 giờ = 09/06 17:00Z, đã qua lúc 10/06 03:00Z
        ReflectionTestUtils.setField(booking, "checkInDate", LocalDate.parse("2009-06-11"));

        service.cancelBooking(ID, new CancelBookingRequest(" đổi lịch "));

        InOrder order = inOrder(bookings, restrictions);
        order.verify(bookings).saveAndFlush(booking);
        order.verify(bookings).findUpdatedAt(ID);
        order.verify(restrictions).evaluateAfterViolation(CUSTOMER, Instant.parse("2009-06-10T03:00:00.000001Z"));
    }

    @Test
    void earlyCancelNeverEvaluatesRestriction() {
        ReflectionTestUtils.setField(booking, "checkInDate", LocalDate.parse("2009-06-20"));

        service.cancelBooking(ID, null);

        verify(restrictions, never()).evaluateAfterViolation(anyLong(), any());
    }

    @Test
    void cancelOfNonBookedIsInvalidTransitionAndWritesNothing() {
        booking.cancel(com.petcare.module.boarding.entity.BookingCancelSource.STAFF, null, false);

        assertThatThrownBy(() -> service.cancelBooking(ID, null)).isInstanceOf(InvalidStateTransitionException.class);

        verify(bookings, never()).saveAndFlush(any());
        verify(restrictions, never()).evaluateAfterViolation(anyLong(), any());
    }
}
