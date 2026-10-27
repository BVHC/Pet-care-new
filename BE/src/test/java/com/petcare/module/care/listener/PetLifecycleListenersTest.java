package com.petcare.module.care.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.lang.reflect.Method;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;

import com.petcare.module.care.service.CareTaskService;
import com.petcare.module.customer.api.PetDeceasedEvent;
import com.petcare.module.customer.api.PetOwnerTransferredEvent;
import com.petcare.module.visit.listener.VisitPetDeceasedListener;
import com.petcare.module.visit.service.FollowUpCancellationService;

/**
 * Listener thú mất / chuyển chủ của care và visit (docs/adr/0084): chỉ ủy quyền cho service, không nuốt lỗi, và thứ tự
 * visit → care ({@code medical_records} trước {@code care_tasks}). Hai module không import nhau ở {@code main} nên quan
 * hệ {@code @Order} do test này ghim: đảo lại là deadlock với đặt lịch khi thú mất chạy song song.
 */
class PetLifecycleListenersTest {

    private final CareTaskService careTasks = mock(CareTaskService.class);
    private final FollowUpCancellationService followUps = mock(FollowUpCancellationService.class);
    private static final PetDeceasedEvent DECEASED = new PetDeceasedEvent(33L, 22L, LocalDate.of(2026, 10, 27), 5L);

    @Test
    void deceasedListenerOfCareCancelsOpenTasksOfThePet() {
        new CarePetDeceasedListener(careTasks).on(DECEASED);

        verify(careTasks).cancelOpenTasksOfDeceasedPet(33L);
        verifyNoMoreInteractions(careTasks);
    }

    @Test
    void deceasedListenerOfVisitCancelsPendingRemindersOfThePet() {
        new VisitPetDeceasedListener(followUps).on(DECEASED);

        verify(followUps).cancelForDeceasedPet(33L);
        verifyNoMoreInteractions(followUps);
    }

    @Test
    void ownerTransferListenerMovesOpenTasksToTheNewOwner() {
        new CarePetOwnerTransferredListener(careTasks).on(new PetOwnerTransferredEvent(33L, 22L, 44L, 5L));

        verify(careTasks).moveOpenTasksToOwner(33L, 44L);
        verifyNoMoreInteractions(careTasks);
    }

    @Test
    void listenerFailuresPropagateToThePublisher() {
        doThrow(new IllegalStateException("boom")).when(careTasks).cancelOpenTasksOfDeceasedPet(33L);
        doThrow(new IllegalStateException("boom")).when(careTasks).moveOpenTasksToOwner(33L, 44L);
        doThrow(new IllegalStateException("boom")).when(followUps).cancelForDeceasedPet(33L);

        assertThatThrownBy(() -> new CarePetDeceasedListener(careTasks).on(DECEASED))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new CarePetOwnerTransferredListener(careTasks)
                .on(new PetOwnerTransferredEvent(33L, 22L, 44L, 5L))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new VisitPetDeceasedListener(followUps).on(DECEASED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void visitListenerRunsBeforeCareListenerOnPetDeceased() throws NoSuchMethodException {
        Method visit = VisitPetDeceasedListener.class.getMethod("on", PetDeceasedEvent.class);
        Method care = CarePetDeceasedListener.class.getMethod("on", PetDeceasedEvent.class);

        assertThat(visit.getAnnotation(EventListener.class)).isNotNull();
        assertThat(care.getAnnotation(EventListener.class)).isNotNull();
        assertThat(visit.getAnnotation(Order.class).value()).isEqualTo(VisitPetDeceasedListener.ORDER);
        assertThat(care.getAnnotation(Order.class).value()).isEqualTo(CarePetDeceasedListener.ORDER);
        assertThat(VisitPetDeceasedListener.ORDER)
                .as("visit khóa medical_records trước khi care khóa care_tasks")
                .isLessThan(CarePetDeceasedListener.ORDER);
    }
}
