package com.petcare.module.boarding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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

import com.petcare.module.boarding.entity.BoardingBooking;
import com.petcare.module.boarding.entity.BoardingBookingStatus;
import com.petcare.module.boarding.entity.BookingChannel;
import com.petcare.module.boarding.fsm.BoardingBookingTransitionHandler;
import com.petcare.module.boarding.repository.BoardingBookingRepository;
import com.petcare.module.boarding.service.BoardingOverdueService.FollowUpResult;
import com.petcare.module.boarding.service.BoardingOverdueService.OverdueResult;
import com.petcare.module.care.api.CareTaskApi;
import com.petcare.module.care.api.CareTaskApi.CareTaskType;
import com.petcare.module.care.api.CareTaskApi.NewCareTask;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.KennelTypeInfo;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.customer.api.PetQueryApi.PetSummary;
import com.petcare.module.customer.api.Species;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.sales.api.BoardingOrderApi;

class BoardingOverdueServiceTest {

    private static final long ID = 88L;
    private static final long PET = 21L;
    private static final long BRANCH = 7L;

    private PetQueryApi pets;
    private BoardingBookingRepository bookings;
    private BoardingOrderApi boardingOrders;
    private CareTaskApi careTasks;
    private BoardingCapacity capacity;
    private BoardingOverdueNotices notices;
    private BoardingOverdueService service;
    private BoardingBooking booking;

    /** Ngày trả dự kiến 12/06/2009; giờ trả quy định 12:00 → mốc quá hạn 05:00Z. */
    private void build(String now) {
        pets = mock(PetQueryApi.class);
        when(pets.findPetForShare(PET)).thenReturn(Optional.of(
                new PetSummary(PET, 14L, "Milu", Species.DOG, null, null, null)));
        bookings = mock(BoardingBookingRepository.class);
        booking = BoardingBooking.book("LT-1", 14L, PET, BRANCH, 5L, LocalDate.parse("2009-06-10"),
                LocalDate.parse("2009-06-12"), 150_000L, BookingChannel.COUNTER, 9L);
        ReflectionTestUtils.setField(booking, "id", ID);
        booking.checkIn(12L, Instant.parse("2009-06-10T03:00:00Z"), LocalDate.parse("2009-06-10"));
        when(bookings.lockCheckedInForOverdue(ID)).thenReturn(Optional.of(ID));
        when(bookings.lockOverdueForFollowUp(ID)).thenReturn(Optional.of(ID));
        when(bookings.findById(ID)).thenReturn(Optional.of(booking));
        when(bookings.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        SystemConfigApi configs = mock(SystemConfigApi.class);
        when(configs.getTime(ConfigKey.BOARDING_CHECKOUT_TIME)).thenReturn(LocalTime.NOON);
        when(configs.getInt(ConfigKey.BOARDING_OVERDUE_CARE_TASK_DAYS)).thenReturn(1);
        when(configs.getInt(ConfigKey.BOARDING_OVERDUE_MANAGER_ALERT_DAYS)).thenReturn(7);
        when(configs.getInt(ConfigKey.BOARDING_CAPACITY_WARNING_DAYS)).thenReturn(2);
        boardingOrders = mock(BoardingOrderApi.class);
        careTasks = mock(CareTaskApi.class);
        capacity = mock(BoardingCapacity.class);
        when(capacity.shortageBookings(anyLong(), anyLong(), any(), any())).thenReturn(List.of());
        CatalogQueryApi catalog = mock(CatalogQueryApi.class);
        when(catalog.findKennelType(5L)).thenReturn(Optional.of(new KennelTypeInfo(5L, "Chuồng chó nhỏ", Species.DOG,
                java.math.BigDecimal.TEN, 150_000L, true)));
        notices = mock(BoardingOverdueNotices.class);
        service = new BoardingOverdueService(pets, catalog, configs, boardingOrders, careTasks, capacity, bookings,
                new BoardingBookingTransitionHandler(), notices, Clock.fixed(Instant.parse(now), ZoneOffset.UTC));
    }

    @BeforeEach
    void setUp() {
        build("2009-06-12T05:03:00Z");           // 12:03 VN ngày trả
    }

    @Test
    void markOverdueLocksPetThenBookingThenWritesThenNotifies() {
        assertThat(service.markOverdue(ID, PET)).isEqualTo(OverdueResult.MARKED);

        InOrder order = inOrder(pets, bookings, boardingOrders, notices);
        order.verify(pets).findPetForShare(PET);
        order.verify(bookings).lockCheckedInForOverdue(ID);
        order.verify(boardingOrders).hasPaidOrder(ID);
        order.verify(bookings).saveAndFlush(booking);
        order.verify(notices).customerOverdue(booking, 1);
        assertThat(booking.getStatus()).isEqualTo(BoardingBookingStatus.OVERDUE);
        assertThat(booking.getLastOverdueNoticeOn()).isEqualTo(LocalDate.parse("2009-06-12"));
    }

    @Test
    void overdueSinceIsTheCheckoutTimeOfTheCheckOutDateNotNow() {
        service.markOverdue(ID, PET);

        assertThat(booking.getOverdueSince()).isEqualTo(Instant.parse("2009-06-12T05:00:00Z"));
    }

    @Test
    void notDueBeforeCheckoutTimeWritesNothing() {
        build("2009-06-12T04:59:59Z");           // 11:59:59 VN

        assertThat(service.markOverdue(ID, PET)).isEqualTo(OverdueResult.NOT_DUE);

        verify(bookings, never()).saveAndFlush(any());
        assertThat(booking.getStatus()).isEqualTo(BoardingBookingStatus.CHECKED_IN);
    }

    @Test
    void paidOrderIsSkipped() {
        when(boardingOrders.hasPaidOrder(ID)).thenReturn(true);

        assertThat(service.markOverdue(ID, PET)).isEqualTo(OverdueResult.PAID_SKIPPED);

        verify(bookings, never()).saveAndFlush(any());
        verify(notices, never()).customerOverdue(any(), anyInt());
    }

    @Test
    void lockedRowIsSkipped() {
        when(bookings.lockCheckedInForOverdue(ID)).thenReturn(Optional.empty());

        assertThat(service.markOverdue(ID, PET)).isEqualTo(OverdueResult.SKIPPED);

        verify(bookings, never()).findById(anyLong());
        verify(bookings, never()).saveAndFlush(any());
    }

    @Test
    void capacityWarningOnlyWhenBookedBookingsInWindowAreShort() {
        service.markOverdue(ID, PET);
        verify(notices, never()).capacityWarning(any(), any(), any());

        BoardingBooking affected = BoardingBooking.book("LT-2", 15L, 22L, BRANCH, 5L, LocalDate.parse("2009-06-13"),
                LocalDate.parse("2009-06-14"), 150_000L, BookingChannel.COUNTER, 9L);
        build("2009-06-12T05:03:00Z");
        when(capacity.shortageBookings(BRANCH, 5L, LocalDate.parse("2009-06-12"), LocalDate.parse("2009-06-14")))
                .thenReturn(List.of(affected));
        service.markOverdue(ID, PET);

        verify(notices).capacityWarning(booking, List.of(affected), "Chuồng chó nhỏ");
    }

    @Test
    void catchUpAfterLongDowntimeCreatesTaskAndNotifiesManagerInTheSameRun() {
        build("2009-06-20T05:03:00Z");           // 8 ngày sau ngày trả

        service.markOverdue(ID, PET);

        ArgumentCaptor<NewCareTask> task = ArgumentCaptor.forClass(NewCareTask.class);
        verify(careTasks).create(task.capture());
        assertThat(task.getValue()).isEqualTo(new NewCareTask(CareTaskType.PICKUP_OVERDUE, ID, BRANCH, 14L, PET,
                LocalDate.parse("2009-06-13")));
        verify(notices).managerOverdue(booking, 8);
        assertThat(booking.getManagerNotifiedAt()).isEqualTo(Instant.parse("2009-06-20T05:03:00Z"));
    }

    private void overdueSince(String instant, String lastNotice) {
        booking.markOverdue(Instant.parse(instant));
        ReflectionTestUtils.setField(booking, "lastOverdueNoticeOn", lastNotice == null ? null
                : LocalDate.parse(lastNotice));
    }

    @Test
    void followUpSendsDailyNoticeOncePerDayFromCheckoutTime() {
        build("2009-06-13T05:01:00Z");           // 12:01 VN ngày kế
        overdueSince("2009-06-12T05:00:00Z", "2009-06-12");

        assertThat(service.followUp(ID, PET)).isEqualTo(FollowUpResult.UPDATED);
        verify(notices).customerOverdue(booking, 1);
        assertThat(booking.getLastOverdueNoticeOn()).isEqualTo(LocalDate.parse("2009-06-13"));

        assertThat(service.followUp(ID, PET)).isEqualTo(FollowUpResult.NOTHING);
        verify(notices).customerOverdue(any(), anyInt());
    }

    @Test
    void followUpDoesNotNoticeBeforeCheckoutTimeOfTheDay() {
        build("2009-06-13T04:00:00Z");           // 11:00 VN
        overdueSince("2009-06-12T05:00:00Z", "2009-06-12");

        assertThat(service.followUp(ID, PET)).isEqualTo(FollowUpResult.NOTHING);

        verify(notices, never()).customerOverdue(any(), anyInt());
    }

    @Test
    void followUpCreatesPickupTaskFromOneDayAndNotifiesManagerOnlyOnce() {
        build("2009-06-19T05:01:00Z");           // 7 ngày sau mốc quá hạn
        overdueSince("2009-06-12T05:00:00Z", "2009-06-19");

        assertThat(service.followUp(ID, PET)).isEqualTo(FollowUpResult.UPDATED);
        verify(careTasks).create(any());
        verify(notices).managerOverdue(booking, 7);

        assertThat(service.followUp(ID, PET)).isEqualTo(FollowUpResult.NOTHING);
        verify(notices).managerOverdue(any(), anyInt());
    }

    @Test
    void followUpSkipsLockedRow() {
        when(bookings.lockOverdueForFollowUp(ID)).thenReturn(Optional.empty());

        assertThat(service.followUp(ID, PET)).isEqualTo(FollowUpResult.SKIPPED);

        verify(careTasks, never()).create(any());
    }
}
