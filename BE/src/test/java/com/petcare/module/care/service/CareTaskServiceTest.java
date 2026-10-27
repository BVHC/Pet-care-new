package com.petcare.module.care.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.care.api.CareTaskApi.CareTaskType;
import com.petcare.module.care.api.CareTaskApi.NewCareTask;
import com.petcare.module.care.entity.CareTask;
import com.petcare.module.care.entity.CareTaskStatus;
import com.petcare.module.care.fsm.CareTaskTransitionHandler;
import com.petcare.module.care.repository.CareTaskRepository;

/**
 * {@link CareTaskService} (docs/adr/0079, trả D047): mỗi nhánh gọi đúng các method repository cần thiết (không câu thừa,
 * tập rỗng không chạm DB), thời điểm lấy từ {@code Clock}, mọi method {@code MANDATORY}.
 */
class CareTaskServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-27T03:00:00Z");
    private static final LocalDate DUE = LocalDate.of(2026, 11, 3);

    private final CareTaskRepository tasks = mock(CareTaskRepository.class);
    private final CareTaskService service = new CareTaskService(tasks, new CareTaskTransitionHandler(),
            Clock.fixed(NOW, ZoneId.of("Asia/Ho_Chi_Minh")));

    private static NewCareTask of(CareTaskType type) {
        return new NewCareTask(type, 500L, 11L, 22L, 33L, DUE);
    }

    private void saveAssignsId(long id) {
        doAnswer(invocation -> {
            CareTask task = invocation.getArgument(0);
            ReflectionTestUtils.setField(task, "id", id);
            return task;
        }).when(tasks).saveAndFlush(any(CareTask.class));
    }

    // ---------------------------------------------------------------- create

    @Test
    void dueTypesAreSavedAndFlushedWithoutAnyOtherCall() {
        saveAssignsId(77L);

        assertThat(service.create(of(CareTaskType.VACCINE_DUE))).isEqualTo(77L);
        assertThat(service.create(of(CareTaskType.FOLLOW_UP_DUE))).isEqualTo(77L);

        ArgumentCaptor<CareTask> saved = ArgumentCaptor.forClass(CareTask.class);
        verify(tasks, org.mockito.Mockito.times(2)).saveAndFlush(saved.capture());
        assertThat(saved.getAllValues()).extracting(CareTask::getTaskType)
                .containsExactly(CareTaskType.VACCINE_DUE, CareTaskType.FOLLOW_UP_DUE);
        assertThat(saved.getAllValues()).allSatisfy(task -> assertThat(task.getStatus()).isEqualTo(CareTaskStatus.OPEN));
        assertThat(saved.getAllValues().get(0).getVaccinationId()).isEqualTo(500L);
        assertThat(saved.getAllValues().get(1).getMedicalRecordId()).isEqualTo(500L);
        verifyNoMoreInteractions(tasks);
    }

    @Test
    void vaccineOverdueInsertsIfAbsentThenReturnsTheIdOfTheRowThatExists() {
        doReturn(0).when(tasks).insertVaccineOverdueIfAbsent(11L, 22L, 33L, 500L, DUE, NOW);
        doReturn(Optional.of(900L)).when(tasks).findVaccineOverdueId(500L);

        assertThat(service.create(of(CareTaskType.VACCINE_OVERDUE))).isEqualTo(900L);

        InOrder order = inOrder(tasks);
        order.verify(tasks).insertVaccineOverdueIfAbsent(11L, 22L, 33L, 500L, DUE, NOW);
        order.verify(tasks).findVaccineOverdueId(500L);
        verifyNoMoreInteractions(tasks);
    }

    @Test
    void pickupOverdueInsertsIfAbsentThenReturnsTheIdOfTheRowThatExists() {
        doReturn(1).when(tasks).insertPickupOverdueIfAbsent(11L, 22L, 33L, 500L, DUE, NOW);
        doReturn(Optional.of(901L)).when(tasks).findPickupOverdueId(500L);

        assertThat(service.create(of(CareTaskType.PICKUP_OVERDUE))).isEqualTo(901L);

        InOrder order = inOrder(tasks);
        order.verify(tasks).insertPickupOverdueIfAbsent(11L, 22L, 33L, 500L, DUE, NOW);
        order.verify(tasks).findPickupOverdueId(500L);
        verifyNoMoreInteractions(tasks);
    }

    @Test
    void anOverdueRowThatCannotBeFoundAfterTheInsertIsACallerError() {
        doReturn(Optional.empty()).when(tasks).findVaccineOverdueId(500L);

        assertThatThrownBy(() -> service.create(of(CareTaskType.VACCINE_OVERDUE)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("READ COMMITTED");
    }

    @Test
    void aNullTaskOrFieldNeverTouchesTheRepository() {
        assertThatThrownBy(() -> service.create(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.create(new NewCareTask(CareTaskType.VACCINE_DUE, 1L, 11L, 22L, 33L, null)))
                .isInstanceOf(NullPointerException.class);

        verifyNoInteractions(tasks);
    }

    // ---------------------------------------------------------------- cancel

    @Test
    void emptyIdsNeverTouchTheRepository() {
        service.cancelForRevaccination(List.of());
        service.cancelFollowUpTasks(List.of());

        verifyNoInteractions(tasks);
    }

    @Test
    void revaccinationCancelsWithTheSystemReasonAtTheClockTime() {
        service.cancelForRevaccination(List.of(600L, 601L));

        verify(tasks).cancelOpenVaccineTasks(List.of(600L, 601L), CareTaskService.REVACCINATED_REASON, NOW);
        verifyNoMoreInteractions(tasks);
    }

    @Test
    void followUpCancellationUsesItsOwnReasonAtTheClockTime() {
        service.cancelFollowUpTasks(List.of(700L));

        verify(tasks).cancelOpenFollowUpTasks(List.of(700L), CareTaskService.FOLLOW_UP_CANCELLED_REASON, NOW);
        verifyNoMoreInteractions(tasks);
    }

    @Test
    void deceasedPetCancelsEveryOpenTaskOfThePetWithTheSystemReasonAtTheClockTime() {
        service.cancelOpenTasksOfDeceasedPet(33L);

        verify(tasks).cancelOpenTasksOfPet(33L, CareTaskService.PET_DECEASED_REASON, NOW);
        verifyNoMoreInteractions(tasks);
    }

    @Test
    void ownerTransferMovesOpenTasksToTheNewOwnerAtTheClockTime() {
        service.moveOpenTasksToOwner(33L, 44L);

        verify(tasks).moveOpenTasksToCustomer(33L, 44L, NOW);
        verifyNoMoreInteractions(tasks);
    }

    @Test
    void theSystemReasonsAreTheAgreedTextAndFitTheColumn() {
        assertThat(CareTaskService.REVACCINATED_REASON).isEqualTo("Đã tiêm lại (BR-TB-03)");
        assertThat(CareTaskService.FOLLOW_UP_CANCELLED_REASON).isEqualTo("Đã có lịch khám / lượt khám mới (BR-TB-06)");
        assertThat(CareTaskService.PET_DECEASED_REASON).isEqualTo("Thú cưng đã mất (BR-KH-05)");
        assertThat(CareTaskService.REVACCINATED_REASON.length()).isLessThanOrEqualTo(CareTask.CANCEL_REASON_MAX_LENGTH);
        assertThat(CareTaskService.FOLLOW_UP_CANCELLED_REASON.length())
                .isLessThanOrEqualTo(CareTask.CANCEL_REASON_MAX_LENGTH);
        assertThat(CareTaskService.PET_DECEASED_REASON.length()).isLessThanOrEqualTo(CareTask.CANCEL_REASON_MAX_LENGTH);
    }

    // ---------------------------------------------------------------- transaction

    @Test
    void everyApiMethodJoinsTheCallersTransactionAndNeverStartsOne() throws NoSuchMethodException {
        for (Method method : new Method[] {
                CareTaskService.class.getMethod("create", NewCareTask.class),
                CareTaskService.class.getMethod("cancelForRevaccination", java.util.Collection.class),
                CareTaskService.class.getMethod("cancelFollowUpTasks", java.util.Collection.class),
                CareTaskService.class.getMethod("cancelOpenTasksOfDeceasedPet", long.class),
                CareTaskService.class.getMethod("moveOpenTasksToOwner", long.class, long.class)}) {
            Transactional transactional = method.getAnnotation(Transactional.class);
            assertThat(transactional).as(method.getName()).isNotNull();
            assertThat(transactional.propagation()).as(method.getName()).isEqualTo(Propagation.MANDATORY);
        }
    }
}
