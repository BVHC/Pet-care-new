package com.petcare.module.appointment.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import com.petcare.module.appointment.service.AppointmentClinicCancellationService;
import com.petcare.module.appointment.service.ClinicCancelCause;
import com.petcare.module.branch.api.BranchClinicCancellationEvent;
import com.petcare.module.branch.api.BranchClinicCancellationEvent.Cause;
import com.petcare.module.customer.api.PetDeceasedEvent;
import com.petcare.module.customer.api.PetOwnerTransferredEvent;

/**
 * Ba listener của Lịch hẹn#5 (docs/adr/0047, convention 07 §7.2, mapping M2): mỗi cái chỉ ủy quyền đúng một lời gọi
 * service với đúng trường của sự kiện; đồng bộ ({@code @EventListener}, không {@code @Async},
 * {@code @TransactionalEventListener}, {@code @Transactional}) — chạy trong transaction của bên phát.
 */
class ClinicCancellationListenersTest {

    private final AppointmentClinicCancellationService service = mock(AppointmentClinicCancellationService.class);

    @Test
    void branchEventCancelsItsAppointmentIdsAndIgnoresBoardingIds() {
        new BranchClinicCancellationListener(service).on(new BranchClinicCancellationEvent(3L, Cause.HOLIDAY_ADDED,
                List.of(11L, 12L), List.of(91L), 5L));

        verify(service).cancelByClinic(List.of(11L, 12L), ClinicCancelCause.HOLIDAY_ADDED);
        verifyNoMoreInteractions(service);
    }

    @Test
    void openingHoursEventMapsToItsOwnCause() {
        new BranchClinicCancellationListener(service).on(new BranchClinicCancellationEvent(3L,
                Cause.OPENING_HOURS_REDUCED, List.of(11L), List.of(), 5L));

        verify(service).cancelByClinic(List.of(11L), ClinicCancelCause.OPENING_HOURS_REDUCED);
    }

    /** Mọi nguyên nhân của branch map được, và về đúng nguyên nhân cùng tên. */
    @ParameterizedTest
    @EnumSource(Cause.class)
    void everyBranchCauseIsMapped(Cause cause) {
        assertThat(BranchClinicCancellationListener.cause(cause).name()).isEqualTo(cause.name());
    }

    @Test
    void petDeceasedCancelsBookedOfThatPet() {
        new PetDeceasedListener(service).on(new PetDeceasedEvent(9L, 7L, LocalDate.of(2026, 10, 18), 5L));

        verify(service).cancelBookedOfPet(9L, ClinicCancelCause.PET_DECEASED);
        verifyNoMoreInteractions(service);
    }

    /** Q8: lịch thuộc chủ lúc đặt; {@code toCustomerId} không dùng. */
    @Test
    void ownerTransferCancelsBookedOfThatPet() {
        new PetOwnerTransferredListener(service).on(new PetOwnerTransferredEvent(9L, 7L, 8L, 5L));

        verify(service).cancelBookedOfPet(9L, ClinicCancelCause.PET_OWNER_TRANSFERRED);
        verifyNoMoreInteractions(service);
    }

    /** C1: đồng bộ, cùng transaction với bên phát; listener không tự mở transaction. */
    @Test
    void listenersAreSynchronousAndTransactionless() {
        for (Class<?> type : List.of(BranchClinicCancellationListener.class, PetDeceasedListener.class,
                PetOwnerTransferredListener.class)) {
            List<Method> handlers = Arrays.stream(type.getDeclaredMethods())
                    .filter(m -> m.isAnnotationPresent(EventListener.class)).toList();
            assertThat(handlers).as(type.getSimpleName()).hasSize(1);
            Method handler = handlers.get(0);
            assertThat(handler.getName()).isEqualTo("on");
            assertThat(handler.isAnnotationPresent(Async.class)).isFalse();
            assertThat(handler.isAnnotationPresent(TransactionalEventListener.class)).isFalse();
            assertThat(handler.isAnnotationPresent(Transactional.class)).isFalse();
            assertThat(type.isAnnotationPresent(Async.class)).isFalse();
            assertThat(type.isAnnotationPresent(Transactional.class)).isFalse();
        }
    }
}
