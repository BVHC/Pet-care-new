package com.petcare.module.boarding.service;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.boarding.dto.DeceasedEndRequest;
import com.petcare.module.boarding.entity.BoardingBooking;
import com.petcare.module.boarding.entity.BoardingBookingStatus;
import com.petcare.module.boarding.entity.BookingChannel;
import com.petcare.module.boarding.entity.BookingEndReason;
import com.petcare.module.boarding.entity.Kennel;
import com.petcare.module.boarding.entity.KennelStatus;
import com.petcare.module.boarding.exception.BoardingConflictException;
import com.petcare.module.boarding.fsm.BoardingBookingTransitionHandler;
import com.petcare.module.boarding.fsm.KennelTransitionHandler;
import com.petcare.module.boarding.repository.BoardingBookingRepository;
import com.petcare.module.boarding.repository.BoardingBookingRepository.BookingKeys;
import com.petcare.module.boarding.repository.KennelRepository;
import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.branch.api.BranchQueryApi.TimeRange;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.KennelTypeInfo;
import com.petcare.module.customer.api.Species;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.sales.api.BoardingOrderApi;
import com.petcare.module.sales.api.BoardingOrderApi.BoardingCharge;
import com.petcare.module.sales.api.BoardingOrderApi.BoardingOrderRef;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

class BoardingCheckoutServiceTest {

    private static final long ID = 88L;
    private static final long BRANCH = 7L;
    private static final long TYPE = 5L;
    private static final long KENNEL = 12L;
    private static final long ACTOR = 3L;

    private BoardingOrderApi boardingOrders;
    private BoardingBookingRepository bookings;
    private KennelRepository kennels;
    private BoardingCheckoutService service;
    private BoardingBooking booking;
    private Kennel kennel;
    private BranchQueryApi branches;

    /** 13:00 giờ VN ngày 12/06/2009 (sau 12:00 quy định). */
    private static Clock clockAt(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }

    @BeforeEach
    void setUp() {
        build("2009-06-12T06:00:00Z");
    }

    private void build(String now) {
        BranchScope scope = mock(BranchScope.class);
        SecurityPrincipal principal = mock(SecurityPrincipal.class);
        when(principal.accountId()).thenReturn(ACTOR);
        when(scope.current()).thenReturn(principal);
        BookingKeys keys = mock(BookingKeys.class);
        when(keys.getBranchId()).thenReturn(BRANCH);
        bookings = mock(BoardingBookingRepository.class);
        when(bookings.findKeys(ID)).thenReturn(Optional.of(keys));
        booking = BoardingBooking.book("LT-1", 14L, 21L, BRANCH, TYPE, LocalDate.parse("2009-06-10"),
                LocalDate.parse("2009-06-14"), 150_000L, BookingChannel.COUNTER, 9L);
        ReflectionTestUtils.setField(booking, "id", ID);
        booking.checkIn(KENNEL, Instant.parse("2009-06-10T03:00:00Z"), LocalDate.parse("2009-06-10"));
        when(bookings.findByIdForUpdate(ID)).thenReturn(Optional.of(booking));
        when(bookings.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        kennel = Kennel.create(BRANCH, TYPE, "C-01", null);
        ReflectionTestUtils.setField(kennel, "id", KENNEL);
        kennel.occupy();
        kennels = mock(KennelRepository.class);
        when(kennels.findByIdForUpdate(KENNEL)).thenReturn(Optional.of(kennel));
        when(kennels.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        branches = mock(BranchQueryApi.class);
        when(branches.openingRangesOn(anyLong(), any())).thenReturn(
                List.of(new TimeRange(LocalTime.of(8, 0), LocalTime.of(19, 0))));
        CatalogQueryApi catalog = mock(CatalogQueryApi.class);
        when(catalog.findKennelType(TYPE)).thenReturn(Optional.of(new KennelTypeInfo(TYPE, "Chuồng chó nhỏ",
                Species.DOG, java.math.BigDecimal.TEN, 150_000L, true)));
        SystemConfigApi configs = mock(SystemConfigApi.class);
        when(configs.getTime(ConfigKey.BOARDING_CHECKOUT_TIME)).thenReturn(LocalTime.NOON);
        boardingOrders = mock(BoardingOrderApi.class);
        when(boardingOrders.openOrReuse(any())).thenReturn(new BoardingOrderRef(501L, 3, 450_000L));
        service = new BoardingCheckoutService(scope, branches, catalog, configs, boardingOrders, bookings, kennels,
                new BoardingBookingTransitionHandler(), new KennelTransitionHandler(), mock(BoardingAssembler.class),
                clockAt(now));
    }

    @Test
    void startCheckoutLocksBookingThenOpensOrderWithSnapshotPriceAndLateNight() {
        service.startCheckout(ID);

        InOrder order = inOrder(bookings, boardingOrders);
        order.verify(bookings).findByIdForUpdate(ID);
        order.verify(boardingOrders).hasPaidOrder(ID);
        ArgumentCaptor<BoardingCharge> charge = ArgumentCaptor.forClass(BoardingCharge.class);
        order.verify(boardingOrders).openOrReuse(charge.capture());
        assertThat(charge.getValue().nights()).isEqualTo(3);          // 10/06 → 12/06 = 2, +1 vì 13:00 > 12:00
        assertThat(charge.getValue().nightlyPrice()).isEqualTo(150_000L);
        assertThat(charge.getValue().kennelTypeServiceId()).isEqualTo(TYPE);
        assertThat(charge.getValue().actorId()).isEqualTo(ACTOR);
        assertThat(charge.getValue().description()).isEqualTo("Lưu trú Chuồng chó nhỏ · LT-1");
    }

    @Test
    void startCheckoutBeforeNoonDoesNotAddTheLateNight() {
        build("2009-06-12T04:00:00Z");           // 11:00 VN

        service.startCheckout(ID);

        ArgumentCaptor<BoardingCharge> charge = ArgumentCaptor.forClass(BoardingCharge.class);
        verify(boardingOrders).openOrReuse(charge.capture());
        assertThat(charge.getValue().nights()).isEqualTo(2);
    }

    @Test
    void startCheckoutWhenAlreadyPaidIs409AndOpensNothing() {
        when(boardingOrders.hasPaidOrder(ID)).thenReturn(true);

        assertThatThrownBy(() -> service.startCheckout(ID)).isInstanceOf(BoardingConflictException.class);

        verify(boardingOrders, never()).openOrReuse(any());
    }

    @Test
    void startCheckoutOutsideOpeningHoursIsBrLt04() {
        when(branches.openingRangesOn(anyLong(), any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.startCheckout(ID)).isInstanceOfSatisfying(
                BusinessRuleViolationException.class, e -> assertThat(e.getRuleId()).isEqualTo("BR-LT-04"));

        verify(boardingOrders, never()).openOrReuse(any());
    }

    @Test
    void handOverWithoutPaidOrderIsBrLt09AndWritesNothing() {
        assertThatThrownBy(() -> service.handOver(ID)).isInstanceOfSatisfying(BusinessRuleViolationException.class,
                e -> assertThat(e.getRuleId()).isEqualTo("BR-LT-09"));

        verify(kennels, never()).findByIdForUpdate(anyLong());
        verify(bookings, never()).saveAndFlush(any());
        assertThat(booking.getStatus()).isEqualTo(BoardingBookingStatus.CHECKED_IN);
    }

    @Test
    void handOverLocksBookingThenChecksPaidThenKennelThenReleases() {
        when(boardingOrders.hasPaidOrder(ID)).thenReturn(true);

        service.handOver(ID);

        InOrder order = inOrder(bookings, boardingOrders, kennels);
        order.verify(bookings).findByIdForUpdate(ID);
        order.verify(boardingOrders).hasPaidOrder(ID);
        order.verify(kennels).findByIdForUpdate(KENNEL);
        order.verify(bookings).saveAndFlush(booking);
        order.verify(kennels).saveAndFlush(kennel);
        assertThat(booking.getStatus()).isEqualTo(BoardingBookingStatus.CHECKED_OUT);
        assertThat(booking.getEndReason()).isEqualTo(BookingEndReason.RETURNED);
        assertThat(kennel.getStatus()).isEqualTo(KennelStatus.AVAILABLE);
    }

    @Test
    void endDeceasedChargesUntilTodayWithoutLateNightAndTrimsNote() {
        service.endDeceased(ID, new DeceasedEndRequest("  Mất lúc 9h  "));

        ArgumentCaptor<BoardingCharge> charge = ArgumentCaptor.forClass(BoardingCharge.class);
        verify(boardingOrders).openOrReuse(charge.capture());
        assertThat(charge.getValue().nights()).isEqualTo(2);
        assertThat(booking.getStatus()).isEqualTo(BoardingBookingStatus.CHECKED_OUT);
        assertThat(booking.getEndReason()).isEqualTo(BookingEndReason.PET_DECEASED);
        assertThat(booking.getEndNote()).isEqualTo("Mất lúc 9h");
        assertThat(kennel.getStatus()).isEqualTo(KennelStatus.AVAILABLE);
    }

    @Test
    void endDeceasedWithPaidOrderReturnsItAndOpensNothing() {
        when(boardingOrders.findPaidOrder(ID)).thenReturn(Optional.of(new BoardingOrderRef(500L, 2, 300_000L)));

        var response = service.endDeceased(ID, new DeceasedEndRequest("Mất"));

        assertThat(response.orderId()).isEqualTo(500L);
        verify(boardingOrders, never()).openOrReuse(any());
    }

    @Test
    void bookedBookingIs409ForAllThree() {
        booking = BoardingBooking.book("LT-2", 14L, 21L, BRANCH, TYPE, LocalDate.parse("2009-06-10"),
                LocalDate.parse("2009-06-14"), 150_000L, BookingChannel.COUNTER, 9L);
        ReflectionTestUtils.setField(booking, "id", ID);
        when(bookings.findByIdForUpdate(ID)).thenReturn(Optional.of(booking));
        when(boardingOrders.hasPaidOrder(ID)).thenReturn(true);

        assertThatThrownBy(() -> service.startCheckout(ID)).isInstanceOf(BoardingConflictException.class);
        assertThatThrownBy(() -> service.handOver(ID))
                .isInstanceOf(com.petcare.platform.exception.InvalidStateTransitionException.class);
        assertThatThrownBy(() -> service.endDeceased(ID, new DeceasedEndRequest("x")))
                .isInstanceOf(com.petcare.platform.exception.InvalidStateTransitionException.class);

        verify(bookings, never()).saveAndFlush(any());
    }
}
