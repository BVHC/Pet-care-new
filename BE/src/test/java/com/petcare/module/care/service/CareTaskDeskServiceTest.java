package com.petcare.module.care.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.boarding.api.BoardingQueryApi;
import com.petcare.module.boarding.api.BoardingQueryApi.StayStatus;
import com.petcare.module.boarding.api.BoardingQueryApi.StaySummary;
import com.petcare.module.care.api.CareTaskApi.CareTaskType;
import com.petcare.module.care.api.CareTaskApi.NewCareTask;
import com.petcare.module.care.dto.CancelCareTaskRequest;
import com.petcare.module.care.dto.CareTaskResponse;
import com.petcare.module.care.dto.CompleteCareTaskRequest;
import com.petcare.module.care.entity.CareTask;
import com.petcare.module.care.entity.CareTaskResult;
import com.petcare.module.care.entity.CareTaskStatus;
import com.petcare.module.care.fsm.CareTaskTransitionHandler;
import com.petcare.module.care.mapper.CareTaskMapperImpl;
import com.petcare.module.care.repository.CareTaskRepository;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi.CustomerContact;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.customer.api.PetQueryApi.PetSummary;
import com.petcare.module.customer.api.Species;
import com.petcare.module.visit.api.VaccinationQueryApi;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/**
 * {@link CareTaskDeskService} (care-v1 #1–4, docs/adr/0080) với repository và các {@code *QueryApi} giả: thứ tự
 * 404 → 403 → 400 BR-TB-05 → khóa → FSM 409 → ghi (G5, G6, G13); lỗi trước khóa không khóa, không ghi; bốn loại
 * {@code context}; chọn nguồn {@code phoneToCall}; danh sách không gọi module nào khi rỗng và kẹp page / size.
 * Mapper là bản sinh thật, nên một cột map nhầm nguồn làm test đỏ.
 */
class CareTaskDeskServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-27T03:00:00.123456789Z");
    private static final Instant NOW_MICROS = Instant.parse("2026-10-27T03:00:00.123456Z");
    private static final LocalDate DUE = LocalDate.of(2026, 11, 3);
    private static final long BRANCH = 11L;
    private static final long ACTOR = 21L;
    private static final long TASK_ID = 311L;

    private final CareTaskRepository tasks = mock(CareTaskRepository.class);
    private final CustomerQueryApi customers = mock(CustomerQueryApi.class);
    private final PetQueryApi pets = mock(PetQueryApi.class);
    private final VaccinationQueryApi vaccinations = mock(VaccinationQueryApi.class);
    private final CatalogQueryApi catalog = mock(CatalogQueryApi.class);
    private final BoardingQueryApi boarding = mock(BoardingQueryApi.class);
    private final BranchScope scope = mock(BranchScope.class);
    private final SecurityPrincipal principal = mock(SecurityPrincipal.class);
    private final CareTaskDeskService service = new CareTaskDeskService(tasks, new CareTaskTransitionHandler(),
            new CareTaskMapperImpl(), customers, pets, vaccinations, catalog, boarding, scope,
            Clock.fixed(NOW, ZoneId.of("Asia/Ho_Chi_Minh")));

    CareTaskDeskServiceTest() {
        doReturn(principal).when(scope).current();
        doReturn(ACTOR).when(principal).accountId();
    }

    private static CareTask task(CareTaskType type, CareTaskStatus status) {
        CareTask task = CareTask.open(new NewCareTask(type, 500L, BRANCH, 22L, 33L, DUE));
        ReflectionTestUtils.setField(task, "id", TASK_ID);
        ReflectionTestUtils.setField(task, "createdAt", Instant.parse("2026-10-20T01:00:00Z"));
        if (status == CareTaskStatus.DONE) {
            task.complete(CareTaskResult.UNREACHABLE, "Không bắt máy", 7L, Instant.parse("2026-10-21T01:00:00Z"));
        } else if (status == CareTaskStatus.CANCELLED) {
            task.cancel("Khách chuyển đi", 7L, Instant.parse("2026-10-21T01:00:00Z"));
        }
        return task;
    }

    private void taskExists(CareTask task) {
        doReturn(Optional.of(BRANCH)).when(tasks).findBranchIdById(TASK_ID);
        doReturn(Optional.of(task)).when(tasks).findByIdForUpdate(TASK_ID);
        doReturn(task).when(tasks).saveAndFlush(task);
    }

    private void relatedRowsExist() {
        doReturn(List.of(new CustomerContact(22L, "Nguyễn Văn An", "0901111111", null, null, false))).when(customers)
                .findContacts(anyCollection());
        doReturn(List.of(new PetSummary(33L, 22L, "Mực", Species.DOG, null, null, null))).when(pets)
                .findPets(anyCollection());
    }

    // ---------------------------------------------------------------- #3 complete

    @Test
    void completeChecksScopeBeforeLockingBeforeTheTransitionBeforeWriting() {
        CareTask task = task(CareTaskType.FOLLOW_UP_DUE, CareTaskStatus.OPEN);
        taskExists(task);
        relatedRowsExist();

        CareTaskResponse response = service.complete(TASK_ID,
                new CompleteCareTaskRequest(CareTaskResult.REACHED, "  Khách hẹn tuần sau \u2003"));

        InOrder order = inOrder(tasks, scope);
        order.verify(tasks).findBranchIdById(TASK_ID);
        order.verify(scope).check(BRANCH);
        order.verify(tasks).findByIdForUpdate(TASK_ID);
        order.verify(tasks).saveAndFlush(task);
        assertThat(task.getStatus()).isEqualTo(CareTaskStatus.DONE);
        assertThat(task.getNote()).isEqualTo("Khách hẹn tuần sau");
        assertThat(task.getHandledBy()).isEqualTo(ACTOR);
        assertThat(task.getHandledAt()).isEqualTo(NOW_MICROS);
        assertThat(response.status()).isEqualTo(CareTaskStatus.DONE);
        assertThat(response.result()).isEqualTo(CareTaskResult.REACHED);
        assertThat(response.handledAt()).isEqualTo(NOW_MICROS);
    }

    @Test
    void completeOfAMissingTaskIs404AndNeverLocksOrWrites() {
        doReturn(Optional.empty()).when(tasks).findBranchIdById(TASK_ID);

        assertThatThrownBy(() -> service.complete(TASK_ID, new CompleteCareTaskRequest(CareTaskResult.REACHED, "x")))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Không tìm thấy việc chăm sóc khách #311");

        verify(tasks).findBranchIdById(TASK_ID);
        verifyNoMoreInteractions(tasks);
        verifyNoInteractions(customers, pets, vaccinations, catalog, boarding);
    }

    @Test
    void completeOfAnotherBranchIs403BeforeTheRequestIsJudgedAndNeverLocks() {
        doReturn(Optional.of(99L)).when(tasks).findBranchIdById(TASK_ID);
        doThrow(new AccessDeniedScopeException("branch:99", "branch:11")).when(scope).check(99L);

        assertThatThrownBy(() -> service.complete(TASK_ID, new CompleteCareTaskRequest(null, "")))
                .isInstanceOf(AccessDeniedScopeException.class);

        verify(tasks, never()).findByIdForUpdate(anyLong());
        verify(tasks, never()).saveAndFlush(any());
    }

    @Test
    void completeWithoutResultOrWithABlankNoteIsBrTb05BeforeTheLockAndWritesNothing() {
        doReturn(Optional.of(BRANCH)).when(tasks).findBranchIdById(TASK_ID);

        for (CompleteCareTaskRequest bad : List.of(new CompleteCareTaskRequest(null, "ghi chú"),
                new CompleteCareTaskRequest(CareTaskResult.REACHED, null),
                new CompleteCareTaskRequest(CareTaskResult.REACHED, ""),
                new CompleteCareTaskRequest(CareTaskResult.REACHED, "   \t\n\u2003"),
                new CompleteCareTaskRequest(null, null))) {
            assertThatThrownBy(() -> service.complete(TASK_ID, bad))
                    .isInstanceOf(BusinessRuleViolationException.class)
                    .hasMessage("Vui lòng chọn kết quả và nhập ghi chú (BR-TB-05)");
        }

        verify(tasks, never()).findByIdForUpdate(anyLong());
        verify(tasks, never()).saveAndFlush(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"DONE", "CANCELLED"})
    void completeOfAClosedTaskIs409AfterTheLockAndWritesNothing(String status) {
        CareTask closed = task(CareTaskType.VACCINE_DUE, CareTaskStatus.valueOf(status));
        taskExists(closed);

        assertThatThrownBy(() -> service.complete(TASK_ID, new CompleteCareTaskRequest(CareTaskResult.REACHED, "x")))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("việc chăm sóc khách");

        InOrder order = inOrder(tasks);
        order.verify(tasks).findByIdForUpdate(TASK_ID);
        verify(tasks, never()).saveAndFlush(any());
        assertThat(closed.getStatus()).isEqualTo(CareTaskStatus.valueOf(status));
    }

    @Test
    void completeThatLosesTheRaceToASystemCancelIsReadAsCancelledUnderTheLockAndIs409() {
        // Câu hủy hệ thống đã commit trong lúc chờ khóa: findByIdForUpdate trả bản CANCELLED (do hệ thống, handledBy null).
        CareTask cancelledBySystem = task(CareTaskType.VACCINE_OVERDUE, CareTaskStatus.OPEN);
        ReflectionTestUtils.setField(cancelledBySystem, "status", CareTaskStatus.CANCELLED);
        taskExists(cancelledBySystem);

        assertThatThrownBy(() -> service.complete(TASK_ID, new CompleteCareTaskRequest(CareTaskResult.REACHED, "x")))
                .isInstanceOf(InvalidStateTransitionException.class);

        verify(tasks, never()).saveAndFlush(any());
    }

    // ---------------------------------------------------------------- #4 cancel

    @Test
    void cancelStoresTheStrippedReasonAndTheActorAndTheMicrosecondClock() {
        CareTask task = task(CareTaskType.PICKUP_OVERDUE, CareTaskStatus.OPEN);
        taskExists(task);
        relatedRowsExist();

        CareTaskResponse response = service.cancel(TASK_ID, new CancelCareTaskRequest("\u2003 Khách đã đón thú \n"));

        InOrder order = inOrder(tasks, scope);
        order.verify(tasks).findBranchIdById(TASK_ID);
        order.verify(scope).check(BRANCH);
        order.verify(tasks).findByIdForUpdate(TASK_ID);
        order.verify(tasks).saveAndFlush(task);
        assertThat(task.getStatus()).isEqualTo(CareTaskStatus.CANCELLED);
        assertThat(task.getCancelReason()).isEqualTo("Khách đã đón thú");
        assertThat(task.getHandledBy()).isEqualTo(ACTOR);
        assertThat(task.getHandledAt()).isEqualTo(NOW_MICROS);
        assertThat(response.cancelReason()).isEqualTo("Khách đã đón thú");
        assertThat(response.result()).isNull();
        assertThat(response.note()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t\n", "\u2003\u3000"})
    void cancelWithABlankReasonIsBrTb05BeforeTheLock(String reason) {
        doReturn(Optional.of(BRANCH)).when(tasks).findBranchIdById(TASK_ID);

        assertThatThrownBy(() -> service.cancel(TASK_ID, new CancelCareTaskRequest(reason)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("Vui lòng nhập lý do hủy (BR-TB-05)");

        verify(tasks, never()).findByIdForUpdate(anyLong());
        verify(tasks, never()).saveAndFlush(any());
    }

    @Test
    void aNoBreakSpaceIsContentNotBlankBecauseStripFollowsCharacterIsWhitespace() {
        // Quy ước của strip() (docs/adr/0080): chỉ tách khoảng trắng Java; NBSP U+00A0 là ký tự có nội dung.
        CareTask task = task(CareTaskType.VACCINE_DUE, CareTaskStatus.OPEN);
        taskExists(task);
        relatedRowsExist();

        service.cancel(TASK_ID, new CancelCareTaskRequest("\u00a0"));

        assertThat(task.getCancelReason()).isEqualTo("\u00a0");
    }

    @Test
    void cancelWithNullReasonIsBrTb05Too() {
        doReturn(Optional.of(BRANCH)).when(tasks).findBranchIdById(TASK_ID);

        assertThatThrownBy(() -> service.cancel(TASK_ID, new CancelCareTaskRequest(null)))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    void cancelOfAClosedTaskIs409AndWritesNothing() {
        CareTask done = task(CareTaskType.VACCINE_DUE, CareTaskStatus.DONE);
        taskExists(done);

        assertThatThrownBy(() -> service.cancel(TASK_ID, new CancelCareTaskRequest("lý do")))
                .isInstanceOf(InvalidStateTransitionException.class);

        verify(tasks, never()).saveAndFlush(any());
        assertThat(done.getCancelReason()).isNull();
    }

    @Test
    void cancelOfAnotherBranchOrMissingNeverReachesTheLock() {
        doReturn(Optional.empty()).when(tasks).findBranchIdById(TASK_ID);
        assertThatThrownBy(() -> service.cancel(TASK_ID, new CancelCareTaskRequest("x")))
                .isInstanceOf(ResourceNotFoundException.class);

        doReturn(Optional.of(99L)).when(tasks).findBranchIdById(TASK_ID);
        doThrow(new AccessDeniedScopeException("branch:99", "branch:11")).when(scope).check(99L);
        assertThatThrownBy(() -> service.cancel(TASK_ID, new CancelCareTaskRequest("x")))
                .isInstanceOf(AccessDeniedScopeException.class);

        verify(tasks, never()).findByIdForUpdate(anyLong());
    }

    // ---------------------------------------------------------------- #2 get

    @Test
    void getChecksTheBranchBeforeAnyOtherModuleIsCalled() {
        CareTask foreign = task(CareTaskType.VACCINE_DUE, CareTaskStatus.OPEN);
        doReturn(Optional.of(foreign)).when(tasks).findById(TASK_ID);
        doThrow(new AccessDeniedScopeException("branch:11", "branch:99")).when(scope).check(BRANCH);

        assertThatThrownBy(() -> service.get(TASK_ID)).isInstanceOf(AccessDeniedScopeException.class);

        verifyNoInteractions(customers, pets, vaccinations, catalog, boarding);
    }

    @Test
    void getOfAMissingTaskIs404() {
        doReturn(Optional.empty()).when(tasks).findById(TASK_ID);

        assertThatThrownBy(() -> service.get(TASK_ID)).isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Không tìm thấy việc chăm sóc khách #311");
    }

    // ---------------------------------------------------------------- ghép dữ liệu

    @Test
    void aVaccineTaskShowsTheCustomersPhoneTheVaccineTypeNameAndOnlyTheCallsItNeeds() {
        CareTask task = task(CareTaskType.VACCINE_OVERDUE, CareTaskStatus.OPEN);
        doReturn(Optional.of(task)).when(tasks).findById(TASK_ID);
        relatedRowsExist();
        doReturn(Map.of(500L, 8L)).when(vaccinations).findVaccineTypeIds(List.of(500L));
        doReturn(Map.of(8L, "Dại")).when(catalog).findVaccineTypeNames(List.of(8L));

        CareTaskResponse response = service.get(TASK_ID);

        assertThat(response.phoneToCall()).isEqualTo("0901111111");
        assertThat(response.context()).isEqualTo("Quá hạn tái chủng Dại");
        assertThat(response.customerName()).isEqualTo("Nguyễn Văn An");
        assertThat(response.petName()).isEqualTo("Mực");
        assertThat(response.vaccinationId()).isEqualTo(500L);
        assertThat(response.medicalRecordVisitId()).isNull();
        assertThat(response.boardingBookingId()).isNull();
        verifyNoInteractions(boarding);
    }

    @Test
    void aPickupTaskCallsTheEmergencyPhoneFromTheCheckInNotTheCustomersPhone() {
        CareTask task = task(CareTaskType.PICKUP_OVERDUE, CareTaskStatus.OPEN);
        doReturn(Optional.of(task)).when(tasks).findById(TASK_ID);
        relatedRowsExist();
        doReturn(Optional.of(new StaySummary(500L, BRANCH, 22L, 33L, StayStatus.OVERDUE, "0988777666"))).when(boarding)
                .findBooking(500L);

        CareTaskResponse response = service.get(TASK_ID);

        assertThat(response.phoneToCall()).isEqualTo("0988777666");
        assertThat(response.context()).isEqualTo("Quá hạn đón thú — đặt chỗ #500");
        assertThat(response.boardingBookingId()).isEqualTo(500L);
        verifyNoInteractions(vaccinations, catalog);
    }

    @Test
    void aPickupTaskWhoseBookingHasNoEmergencyPhoneYetHasNoPhoneToCall() {
        CareTask task = task(CareTaskType.PICKUP_OVERDUE, CareTaskStatus.OPEN);
        doReturn(Optional.of(task)).when(tasks).findById(TASK_ID);
        relatedRowsExist();
        doReturn(Optional.of(new StaySummary(500L, BRANCH, 22L, 33L, StayStatus.OVERDUE, null))).when(boarding)
                .findBooking(500L);

        assertThat(service.get(TASK_ID).phoneToCall()).isNull();
    }

    @Test
    void aMissingCustomerOrPetIsBadDataNotANullInARequiredField() {
        CareTask task = task(CareTaskType.FOLLOW_UP_DUE, CareTaskStatus.OPEN);
        doReturn(Optional.of(task)).when(tasks).findById(TASK_ID);
        doReturn(List.of()).when(customers).findContacts(anyCollection());
        doReturn(List.of()).when(pets).findPets(anyCollection());

        assertThatThrownBy(() -> service.get(TASK_ID)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Khách #22");
    }

    @Test
    void contextHasOneShapePerTypeAndDropsAMissingVaccineName() {
        assertThat(CareTaskDeskService.context(task(CareTaskType.VACCINE_DUE, CareTaskStatus.OPEN), "5 bệnh"))
                .isEqualTo("Tái chủng 5 bệnh");
        assertThat(CareTaskDeskService.context(task(CareTaskType.VACCINE_DUE, CareTaskStatus.OPEN), null))
                .isEqualTo("Tái chủng");
        assertThat(CareTaskDeskService.context(task(CareTaskType.VACCINE_OVERDUE, CareTaskStatus.OPEN), "Dại"))
                .isEqualTo("Quá hạn tái chủng Dại");
        assertThat(CareTaskDeskService.context(task(CareTaskType.VACCINE_OVERDUE, CareTaskStatus.OPEN), null))
                .isEqualTo("Quá hạn tái chủng");
        assertThat(CareTaskDeskService.context(task(CareTaskType.FOLLOW_UP_DUE, CareTaskStatus.OPEN), null))
                .isEqualTo("Tái khám ngày 03/11/2026");
        assertThat(CareTaskDeskService.context(task(CareTaskType.PICKUP_OVERDUE, CareTaskStatus.OPEN), null))
                .isEqualTo("Quá hạn đón thú — đặt chỗ #500");
    }

    // ---------------------------------------------------------------- #1 danh sách

    @Test
    @SuppressWarnings("unchecked")
    void listUsesTheReceptionistsBranchOpenByDefaultSortedByDueDateThenIdAndClampsPaging() {
        doReturn(BRANCH).when(scope).resolve(null);
        Page<CareTask> empty = new PageImpl<>(List.of(), PageRequest.of(0, 1), 0);
        doReturn(empty).when(tasks).findAll(any(Specification.class), any(Pageable.class));

        PageResponse<CareTaskResponse> result = service.list(null, null, null, null, -4, 1000);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(tasks).findAll(any(Specification.class), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isZero();
        assertThat(pageable.getValue().getPageSize()).isEqualTo(100);
        assertThat(pageable.getValue().getSort()).isEqualTo(Sort.by(Sort.Direction.ASC, "dueDate", "id"));
        assertThat(result.content()).isEmpty();
        verify(scope).resolve(null);
        verifyNoInteractions(customers, pets, vaccinations, catalog, boarding);
    }

    @Test
    @SuppressWarnings("unchecked")
    void listClampsASizeBelowOneToOne() {
        doReturn(BRANCH).when(scope).resolve(null);
        doReturn(new PageImpl<>(List.<CareTask>of())).when(tasks).findAll(any(Specification.class), any(Pageable.class));

        service.list(CareTaskStatus.DONE, CareTaskType.VACCINE_DUE, DUE, DUE.plusDays(1), 3, 0);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(tasks).findAll(any(Specification.class), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(3);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(1);
    }

    @Test
    void aStaffMemberWithoutABranchCannotListTheDesk() {
        doReturn((Long) null).when(scope).resolve(null);

        assertThatThrownBy(() -> service.list(null, null, null, null, 0, 20))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(tasks);
    }

    @Test
    @SuppressWarnings("unchecked")
    void listJoinsTheWholePageWithOneCallPerSourceAndOnlyForIdsThatExist() {
        doReturn(BRANCH).when(scope).resolve(null);
        CareTask first = task(CareTaskType.VACCINE_DUE, CareTaskStatus.OPEN);
        CareTask second = task(CareTaskType.VACCINE_DUE, CareTaskStatus.OPEN);
        ReflectionTestUtils.setField(second, "id", TASK_ID + 1);
        ReflectionTestUtils.setField(second, "vaccinationId", 501L);
        doReturn(new PageImpl<>(List.of(first, second), PageRequest.of(0, 20), 2)).when(tasks)
                .findAll(any(Specification.class), any(Pageable.class));
        relatedRowsExist();
        doReturn(Map.of(500L, 8L, 501L, 8L)).when(vaccinations).findVaccineTypeIds(List.of(500L, 501L));
        doReturn(Map.of(8L, "Dại")).when(catalog).findVaccineTypeNames(List.of(8L));

        PageResponse<CareTaskResponse> result = service.list(null, null, null, null, 0, 20);

        assertThat(result.content()).extracting(CareTaskResponse::careTaskId).containsExactly(TASK_ID, TASK_ID + 1);
        assertThat(result.content()).extracting(CareTaskResponse::context).containsOnly("Tái chủng Dại");
        assertThat(result.totalElements()).isEqualTo(2);
        verify(customers).findContacts(List.of(22L));
        verify(pets).findPets(List.of(33L));
        verify(vaccinations).findVaccineTypeIds(List.of(500L, 501L));
        verify(catalog).findVaccineTypeNames(List.of(8L));
        verifyNoInteractions(boarding);
    }
}
