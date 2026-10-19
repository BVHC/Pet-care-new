package com.petcare.module.appointment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.petcare.module.appointment.api.BookingRestrictionApi;
import com.petcare.module.appointment.entity.Appointment;
import com.petcare.module.appointment.entity.AppointmentChannel;
import com.petcare.module.appointment.entity.AppointmentStatus;
import com.petcare.module.appointment.fsm.AppointmentTransitionHandler;
import com.petcare.module.appointment.repository.AppointmentRepository;
import com.petcare.module.appointment.service.AppointmentNoShowService.NoShowResult;
import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.platform.exception.InvalidStateTransitionException;

/** {@link AppointmentNoShowService} (docs/adr/0048): thứ tự, mọi nhánh không ghi gì, ranh giới transaction. */
class AppointmentNoShowServiceTest {

    private static final long ID = 701L;
    private static final long CUSTOMER = 7L;
    private static final LocalDate DAY = LocalDate.of(2026, 10, 19);
    private static final LocalTime SLOT = LocalTime.of(9, 0);
    private static final Instant NOW = Instant.parse("2026-10-19T03:00:00.123456789Z");

    private final AppointmentRepository appointments = mock(AppointmentRepository.class);
    private final BookingRestrictionApi restrictions = mock(BookingRestrictionApi.class);
    private final SystemConfigApi configs = mock(SystemConfigApi.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final AppointmentNoShowService service = new AppointmentNoShowService(appointments,
            new AppointmentTransitionHandler(), restrictions, configs, clock);
    private final NoShowCutoff cutoff = new NoShowCutoff(DAY, LocalTime.of(9, 30));

    @AfterEach
    void clearReadOnlyFlag() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }

    private static Appointment appointment(AppointmentStatus status, LocalTime slot) {
        Appointment appointment = Appointment.book("LH-261019-0701", CUSTOMER, 9L, 3L, 11L, ServiceGroup.MEDICAL, DAY,
                slot, AppointmentChannel.ONLINE, 1L, null);
        set(appointment, "id", ID);
        set(appointment, "status", status);
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

    private void locked(Appointment appointment) {
        when(appointments.lockOverdueBooked(ID, cutoff.date(), cutoff.time().toString())).thenReturn(Optional.of(ID));
        when(appointments.findById(ID)).thenReturn(Optional.of(appointment));
        when(appointments.saveAndFlush(any(Appointment.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private void assertNothingWritten() {
        verify(appointments, never()).saveAndFlush(any());
        verifyNoInteractions(restrictions);
    }

    @Test
    void marksThenEvaluatesInOrderWithMicrosecondInstantAfterLock() {
        Appointment appointment = appointment(AppointmentStatus.BOOKED, SLOT);
        locked(appointment);

        assertThat(service.markNoShow(ID, cutoff)).isEqualTo(NoShowResult.MARKED);

        InOrder order = inOrder(appointments, restrictions);
        order.verify(appointments).lockOverdueBooked(ID, cutoff.date(), cutoff.time().toString());
        order.verify(appointments).findById(ID);
        order.verify(appointments).saveAndFlush(appointment);
        order.verify(restrictions).evaluateAfterViolation(CUSTOMER, Instant.parse("2026-10-19T03:00:00.123456Z"));
        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.NO_SHOW);
        assertThat(appointment.isLateCancel()).isFalse();
        assertThat(appointment.getCancelSource()).isNull();
        assertThat(appointment.getCancelledAt()).isNull();
    }

    @Test
    void notLockedIsSkippedWithoutAnyRead() {
        when(appointments.lockOverdueBooked(ID, cutoff.date(), cutoff.time().toString())).thenReturn(Optional.empty());

        assertThat(service.markNoShow(ID, cutoff)).isEqualTo(NoShowResult.SKIPPED);

        verify(appointments, never()).findById(anyLong());
        assertNothingWritten();
    }

    @Test
    void rowVanishedAfterLockIsIllegalState() {
        when(appointments.lockOverdueBooked(ID, cutoff.date(), cutoff.time().toString())).thenReturn(Optional.of(ID));
        when(appointments.findById(ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markNoShow(ID, cutoff)).isInstanceOf(IllegalStateException.class);
        assertNothingWritten();
    }

    @Test
    void notOverdueAfterReadIsIllegalState() {
        locked(appointment(AppointmentStatus.BOOKED, LocalTime.of(9, 30)));

        assertThatThrownBy(() -> service.markNoShow(ID, cutoff)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chưa quá hạn");
        assertNothingWritten();
    }

    @Test
    void nonBookedRowIsRejectedByFsm() {
        locked(appointment(AppointmentStatus.CHECKED_IN, SLOT));

        assertThatThrownBy(() -> service.markNoShow(ID, cutoff)).isInstanceOf(InvalidStateTransitionException.class);
        assertNothingWritten();
    }

    @Test
    void evaluationFailurePropagatesUnchanged() {
        locked(appointment(AppointmentStatus.BOOKED, SLOT));
        doThrow(new UnsupportedOperationException("D033")).when(restrictions).evaluateAfterViolation(anyLong(), any());

        assertThatThrownBy(() -> service.markNoShow(ID, cutoff)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void readOnlyTransactionRejectedBeforeAnyLock() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);

        assertThatThrownBy(() -> service.markNoShow(ID, cutoff)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(appointments, restrictions);
    }

    @Test
    void cutoffReadsConfigOnEveryCall() {
        when(configs.getInt(ConfigKey.APPOINTMENT_NO_SHOW_AFTER_MINUTES)).thenReturn(30, 60);

        assertThat(service.cutoff(Instant.parse("2026-10-19T03:00:00Z")).time()).isEqualTo(LocalTime.of(9, 30));
        assertThat(service.cutoff(Instant.parse("2026-10-19T03:00:00Z")).time()).isEqualTo(LocalTime.of(9, 0));
    }

    @Test
    void transactionBoundaries() throws Exception {
        Transactional write = AppointmentNoShowService.class.getMethod("markNoShow", long.class, NoShowCutoff.class)
                .getAnnotation(Transactional.class);
        assertThat(write).isNotNull();
        assertThat(write.readOnly()).isFalse();
        assertThat(write.noRollbackFor()).isEmpty();
        assertThat(write.propagation()).isEqualTo(Propagation.REQUIRED);
        assertThat(write.isolation()).isEqualTo(Isolation.DEFAULT);

        Transactional read = AppointmentNoShowService.class
                .getMethod("findOverdueIds", NoShowCutoff.class, long.class, int.class).getAnnotation(Transactional.class);
        assertThat(read.readOnly()).isTrue();
        assertThat(AppointmentNoShowService.class.getAnnotation(Transactional.class)).isNull();
    }
}
