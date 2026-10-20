package com.petcare.module.appointment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.petcare.module.appointment.api.BookingRestrictionApi;
import com.petcare.module.appointment.entity.Appointment;
import com.petcare.module.appointment.entity.AppointmentCancelSource;
import com.petcare.module.appointment.entity.AppointmentChannel;
import com.petcare.module.appointment.entity.AppointmentStatus;
import com.petcare.module.appointment.fsm.AppointmentTransitionHandler;
import com.petcare.module.appointment.repository.AppointmentRepository;
import com.petcare.module.catalog.api.ServiceGroup;

/**
 * {@link AppointmentClinicCancellationService} (Lịch hẹn#5, docs/adr/0052): khử trùng + sắp id tăng dần, khóa theo lô
 * trong một câu, {@code now} sau khóa, kiểm lại {@code BOOKED} sau khóa (khác thì bỏ qua), {@code CLINIC} / không hủy
 * muộn / không đổi số lần đổi, id thiếu → lỗi không ghi gì, thông báo sau khi ghi. Commit / rollback thật:
 * {@code ClinicCancellationIT}.
 */
class AppointmentClinicCancellationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-18T03:04:05.123456789Z");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 25);

    private final AppointmentRepository appointments = mock(AppointmentRepository.class);
    private final ClinicCancellationNotices notices = mock(ClinicCancellationNotices.class);
    private final Clock clock = mock(Clock.class);
    private final AppointmentClinicCancellationService service =
            new AppointmentClinicCancellationService(appointments, new AppointmentTransitionHandler(), notices, clock);

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW);
        when(appointments.saveAndFlush(any(Appointment.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void clearReadOnlyFlag() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }

    private static Appointment appointment(long id, AppointmentStatus status) {
        Appointment appointment = Appointment.book("LH-261025-" + id, 7L, 9L, 3L, 11L, ServiceGroup.MEDICAL, DAY,
                LocalTime.of(9, 0), AppointmentChannel.ONLINE, 1L, null);
        set(appointment, "id", id);
        set(appointment, "status", status);
        set(appointment, "rescheduleCount", (short) 2);
        if (status == AppointmentStatus.CANCELLED) {
            set(appointment, "cancelSource", AppointmentCancelSource.CUSTOMER);
            set(appointment, "lateCancel", true);
        }
        return appointment;
    }

    private static void set(Object target, String field, Object value) {
        try {
            Field f = Appointment.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void cancelsBookedAscendingInOneLockStatementThenNotifies() {
        Appointment two = appointment(2L, AppointmentStatus.BOOKED);
        Appointment five = appointment(5L, AppointmentStatus.BOOKED);
        Appointment nine = appointment(9L, AppointmentStatus.BOOKED);
        when(appointments.findAllByIdInForUpdate(List.of(2L, 5L, 9L))).thenReturn(List.of(two, five, nine));

        List<Long> cancelled = service.cancelByClinic(List.of(5L, 2L, 5L, 9L), ClinicCancelCause.HOLIDAY_ADDED);

        assertThat(cancelled).containsExactly(2L, 5L, 9L);
        InOrder order = inOrder(appointments, clock, notices);
        order.verify(appointments).findAllByIdInForUpdate(List.of(2L, 5L, 9L));
        order.verify(clock).instant();
        order.verify(appointments).saveAndFlush(two);
        order.verify(appointments).saveAndFlush(five);
        order.verify(appointments).saveAndFlush(nine);
        order.verify(notices).enqueueFor(List.of(two, five, nine), ClinicCancelCause.HOLIDAY_ADDED);
        for (Appointment a : List.of(two, five, nine)) {
            assertThat(a.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
            assertThat(a.getCancelSource()).isEqualTo(AppointmentCancelSource.CLINIC);
            assertThat(a.isLateCancel()).isFalse();
            assertThat(a.getCancelReason()).isEqualTo(ClinicCancelCause.HOLIDAY_ADDED.reason());
            assertThat(a.getCancelledAt()).isEqualTo(Instant.parse("2026-10-18T03:04:05.123456Z"));
            assertThat(a.getRescheduleCount()).as("BR-LH-10: không tính lần đổi").isEqualTo((short) 2);
        }
    }

    /** Q4: danh sách id tính trước khi khóa; lịch đã rời BOOKED trong lúc chờ thì bỏ qua, không lỗi, không thông báo. */
    @Test
    void skipsAppointmentsNoLongerBookedAfterTheLock() {
        Appointment booked = appointment(1L, AppointmentStatus.BOOKED);
        Appointment checkedIn = appointment(2L, AppointmentStatus.CHECKED_IN);
        Appointment byCustomer = appointment(3L, AppointmentStatus.CANCELLED);
        Appointment noShow = appointment(4L, AppointmentStatus.NO_SHOW);
        Appointment completed = appointment(5L, AppointmentStatus.COMPLETED);
        when(appointments.findAllByIdInForUpdate(List.of(1L, 2L, 3L, 4L, 5L)))
                .thenReturn(List.of(booked, checkedIn, byCustomer, noShow, completed));

        List<Long> cancelled = service.cancelByClinic(List.of(1L, 2L, 3L, 4L, 5L), ClinicCancelCause.PET_DECEASED);

        assertThat(cancelled).containsExactly(1L);
        verify(appointments).saveAndFlush(booked);
        verify(appointments, never()).saveAndFlush(checkedIn);
        verify(appointments, never()).saveAndFlush(byCustomer);
        verify(notices).enqueueFor(List.of(booked), ClinicCancelCause.PET_DECEASED);
        assertThat(checkedIn.getStatus()).isEqualTo(AppointmentStatus.CHECKED_IN);
        assertThat(byCustomer.getCancelSource()).as("giữ lần hủy của khách").isEqualTo(AppointmentCancelSource.CUSTOMER);
        assertThat(byCustomer.isLateCancel()).isTrue();
        assertThat(noShow.getStatus()).isEqualTo(AppointmentStatus.NO_SHOW);
        assertThat(completed.getStatus()).isEqualTo(AppointmentStatus.COMPLETED);
    }

    @Test
    void allSkippedStillCallsNoticesWithEmptyList() {
        Appointment checkedIn = appointment(2L, AppointmentStatus.CHECKED_IN);
        when(appointments.findAllByIdInForUpdate(List.of(2L))).thenReturn(List.of(checkedIn));

        assertThat(service.cancelByClinic(List.of(2L), ClinicCancelCause.HOLIDAY_ADDED)).isEmpty();

        verify(appointments, never()).saveAndFlush(any());
        verify(notices).enqueueFor(List.of(), ClinicCancelCause.HOLIDAY_ADDED);
    }

    /** Lịch hẹn không bao giờ bị xóa cứng: id thiếu là dữ liệu hỏng → 500, không ghi, không thông báo. */
    @Test
    void missingIdThrowsBeforeAnyWrite() {
        when(appointments.findAllByIdInForUpdate(List.of(2L, 5L)))
                .thenReturn(List.of(appointment(2L, AppointmentStatus.BOOKED)));

        assertThatThrownBy(() -> service.cancelByClinic(List.of(2L, 5L), ClinicCancelCause.HOLIDAY_ADDED))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("không tồn tại");

        verify(appointments, never()).saveAndFlush(any());
        verifyNoInteractions(notices);
        verify(clock, never()).instant();
    }

    @Test
    void emptyIdsTouchNothing() {
        assertThat(service.cancelByClinic(List.of(), ClinicCancelCause.OPENING_HOURS_REDUCED)).isEmpty();

        verifyNoInteractions(appointments, notices, clock);
    }

    /** Giới hạn tải: ≤ 100 id mỗi câu khóa, các lô nối tiếp nhau theo id tăng dần (thứ tự toàn cục giữ nguyên). */
    @Test
    void locksInAscendingBatchesOfAtMostOneHundred() {
        List<Long> ids = LongStream.rangeClosed(1, 150).boxed().collect(Collectors.toCollection(ArrayList::new));
        java.util.Collections.reverse(ids);
        when(appointments.findAllByIdInForUpdate(anyCollection())).thenAnswer(inv -> {
            Collection<Long> batch = inv.getArgument(0);
            return batch.stream().map(id -> appointment(id, AppointmentStatus.BOOKED)).toList();
        });

        assertThat(service.cancelByClinic(ids, ClinicCancelCause.HOLIDAY_ADDED)).hasSize(150);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> batches = ArgumentCaptor.forClass(Collection.class);
        verify(appointments, org.mockito.Mockito.times(2)).findAllByIdInForUpdate(batches.capture());
        assertThat(batches.getAllValues().get(0)).containsExactlyElementsOf(LongStream.rangeClosed(1, 100).boxed()
                .toList());
        assertThat(batches.getAllValues().get(1)).containsExactlyElementsOf(LongStream.rangeClosed(101, 150).boxed()
                .toList());
    }

    @Test
    void cancelBookedOfPetUsesTheBookedIdsOfThatPet() {
        Appointment booked = appointment(4L, AppointmentStatus.BOOKED);
        when(appointments.findBookedIdsByPetId(9L)).thenReturn(List.of(4L));
        when(appointments.findAllByIdInForUpdate(List.of(4L))).thenReturn(List.of(booked));

        assertThat(service.cancelBookedOfPet(9L, ClinicCancelCause.PET_OWNER_TRANSFERRED)).containsExactly(4L);

        assertThat(booked.getCancelReason()).isEqualTo(ClinicCancelCause.PET_OWNER_TRANSFERRED.reason());
        verify(notices).enqueueFor(List.of(booked), ClinicCancelCause.PET_OWNER_TRANSFERRED);
    }

    @Test
    void petWithoutBookedAppointmentsTouchesNothingElse() {
        when(appointments.findBookedIdsByPetId(9L)).thenReturn(List.of());

        assertThat(service.cancelBookedOfPet(9L, ClinicCancelCause.PET_DECEASED)).isEmpty();

        verify(appointments, never()).findAllByIdInForUpdate(anyCollection());
        verify(notices, never()).enqueueFor(anyList(), eq(ClinicCancelCause.PET_DECEASED));
    }

    @Test
    void readOnlyTransactionRejectedBeforeAnyRead() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);

        assertThatThrownBy(() -> service.cancelByClinic(List.of(1L), ClinicCancelCause.HOLIDAY_ADDED))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("readOnly");
        assertThatThrownBy(() -> service.cancelBookedOfPet(9L, ClinicCancelCause.PET_DECEASED))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(appointments, notices);
    }

    @Test
    void nullArgumentsRejected() {
        assertThatThrownBy(() -> service.cancelByClinic(null, ClinicCancelCause.HOLIDAY_ADDED))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.cancelByClinic(List.of(1L), null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.cancelBookedOfPet(null, ClinicCancelCause.PET_DECEASED))
                .isInstanceOf(NullPointerException.class);
        verifyNoInteractions(appointments);
    }

    /** C2 (docs/adr/0052): tham gia transaction của bên phát, không mở transaction riêng, không nuốt lỗi. */
    @Test
    void everyPublicMethodJoinsTheCallerTransactionAndNeverCommitsOnError() throws NoSuchMethodException {
        for (Method method : List.of(
                AppointmentClinicCancellationService.class.getMethod("cancelByClinic", Collection.class,
                        ClinicCancelCause.class),
                AppointmentClinicCancellationService.class.getMethod("cancelBookedOfPet", Long.class,
                        ClinicCancelCause.class))) {
            Transactional tx = method.getAnnotation(Transactional.class);
            assertThat(tx).as(method.getName()).isNotNull();
            assertThat(tx.propagation()).isEqualTo(Propagation.MANDATORY);
            assertThat(tx.noRollbackFor()).isEmpty();
            assertThat(tx.noRollbackForClassName()).isEmpty();
        }
    }

    /** Hủy do phòng khám không tính BR-LH-09: service không phụ thuộc {@code BookingRestrictionApi}. */
    @Test
    void doesNotDependOnBookingRestrictions() {
        Set<Class<?>> dependencies = Arrays.stream(AppointmentClinicCancellationService.class.getConstructors())
                .flatMap(c -> Arrays.stream(c.getParameterTypes())).collect(Collectors.toSet());

        assertThat(dependencies).doesNotContain(BookingRestrictionApi.class);
    }

    @ParameterizedTest
    @EnumSource(ClinicCancelCause.class)
    void everyCauseHasAShortVietnameseReason(ClinicCancelCause cause) {
        assertThat(cause.reason()).isNotBlank().hasSizeLessThanOrEqualTo(300);
    }

    @Test
    void causesHaveDistinctReasons() {
        assertThat(Arrays.stream(ClinicCancelCause.values()).map(ClinicCancelCause::reason).distinct())
                .hasSize(ClinicCancelCause.values().length);
    }
}
