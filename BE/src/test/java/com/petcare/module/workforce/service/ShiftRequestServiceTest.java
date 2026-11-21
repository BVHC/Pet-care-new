package com.petcare.module.workforce.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.branch.api.BranchQueryApi.BranchStatus;
import com.petcare.module.branch.api.BranchQueryApi.BranchSummary;
import com.petcare.module.care.api.NotificationApi;
import com.petcare.module.care.api.NotificationApi.NotificationRequest;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.NotificationTemplateCode;
import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.api.StaffDirectoryApi;
import com.petcare.module.identity.api.StaffDirectoryApi.StaffSummary;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.workforce.dto.CreateShiftRequestRequest;
import com.petcare.module.workforce.dto.ShiftRequestResponse;
import com.petcare.module.workforce.entity.ShiftAssignment;
import com.petcare.module.workforce.entity.ShiftAssignmentSource;
import com.petcare.module.workforce.entity.ShiftDefinition;
import com.petcare.module.workforce.entity.ShiftRequest;
import com.petcare.module.workforce.entity.ShiftRequestStatus;
import com.petcare.module.workforce.entity.ShiftRequestType;
import com.petcare.module.workforce.fsm.ShiftRequestTransitionHandler;
import com.petcare.module.workforce.mapper.ShiftRequestMapper;
import com.petcare.module.workforce.repository.ShiftAssignmentRepository;
import com.petcare.module.workforce.repository.ShiftAssignmentRepository.AssignmentScope;
import com.petcare.module.workforce.repository.ShiftAssignmentRepository.OverlappingShift;
import com.petcare.module.workforce.repository.ShiftDefinitionRepository;
import com.petcare.module.workforce.repository.ShiftRequestRepository;
import com.petcare.module.workforce.repository.ShiftRequestRepository.RequestOwner;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/**
 * {@link ShiftRequestService} (workforce-v1 #9–11, docs/adr/0138 bước 6): thứ tự đọc không khóa → khóa → đọc lại → guard
 * → ghi → thông báo → map; mọi nhánh lỗi không ghi và không thông báo; danh tính luôn từ principal; ghép danh sách theo
 * lô; cấu hình transaction của ba use case.
 */
class ShiftRequestServiceTest {

    private static final long BRANCH = 5L;
    private static final long OTHER_BRANCH = 6L;
    private static final long STAFF = 12L;
    private static final long COLLEAGUE = 13L;
    private static final long MORNING = 7L;
    private static final long AFTERNOON = 8L;
    private static final long ASSIGNMENT = 31L;
    private static final long REQUEST = 900L;
    private static final LocalDate WORK_DAY = LocalDate.of(2026, 11, 24);
    /** 10:00 giờ Việt Nam, thứ Ba 17/11/2026. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-17T03:00:00Z"), TimeConfig.BUSINESS_ZONE);

    private final BranchScope branchScope = mock(BranchScope.class);
    private final StaffDirectoryApi staffDirectory = mock(StaffDirectoryApi.class);
    private final ShiftDefinitionRepository definitions = mock(ShiftDefinitionRepository.class);
    private final ShiftAssignmentRepository assignments = mock(ShiftAssignmentRepository.class);
    private final ShiftRequestRepository requests = mock(ShiftRequestRepository.class);
    private final ShiftRequestMapper mapper = spy(Mappers.getMapper(ShiftRequestMapper.class));
    private final ShiftRequestTransitionHandler transitions = new ShiftRequestTransitionHandler();
    private final NotificationApi notifications = mock(NotificationApi.class);
    private final BranchQueryApi branches = mock(BranchQueryApi.class);
    private final SystemConfigApi configs = mock(SystemConfigApi.class);
    private final SecurityPrincipal principal = mock(SecurityPrincipal.class);

    private ShiftRequestService service;
    private ShiftAssignment assignment;

    @BeforeEach
    void setUp() {
        service = new ShiftRequestService(branchScope, staffDirectory, definitions, assignments, requests, mapper,
                transitions, notifications, branches, configs, CLOCK);
        when(branchScope.current()).thenReturn(principal);
        when(principal.accountId()).thenReturn(STAFF);
        when(principal.branchId()).thenReturn(BRANCH);
        when(configs.getInt(ConfigKey.WORKFORCE_REQUEST_LEAD_HOURS)).thenReturn(24);
        when(staffDirectory.findStaffForUpdate(STAFF)).thenReturn(Optional.of(staff(STAFF)));
        when(staffDirectory.findStaffByIds(anyCollection())).thenReturn(List.of(staff(STAFF)));
        when(staffDirectory.findActiveStaffIds(BRANCH, Role.BRANCH_MANAGER)).thenReturn(List.of(90L, 91L));
        when(branches.findBranch(BRANCH)).thenReturn(Optional.of(new BranchSummary(BRANCH, "Chi nhánh Quận 1",
                "1 Lê Lợi", "0900000000", BranchStatus.ACTIVE, false)));
        when(requests.saveAndFlush(any(ShiftRequest.class))).thenAnswer(call -> {
            ShiftRequest saved = call.getArgument(0);
            if (saved.getId() == null) {
                ReflectionTestUtils.setField(saved, "id", REQUEST);
            }
            return saved;
        });
        assignment = givenAssignment(WORK_DAY, MORNING);
        givenDefinition(MORNING, BRANCH, true, "Ca sáng", LocalTime.of(8, 0), LocalTime.of(12, 0));
        givenDefinition(AFTERNOON, BRANCH, true, "Ca chiều", LocalTime.of(13, 0), LocalTime.of(17, 0));
        when(assignments.findOverlapping(anyLong(), any(), any(), any())).thenReturn(List.of());
    }

    // ---------------------------------------------------------------- #9: thứ tự và kết quả

    @Test
    void createReadsWithoutLockingThenLocksAccountsThenAssignmentThenRequestThenWritesThenNotifiesThenMaps() {
        service.createShiftRequest(leave());

        InOrder order = inOrder(branchScope, assignments, staffDirectory, requests, notifications, mapper);
        order.verify(branchScope).current();
        order.verify(assignments).findScopeById(ASSIGNMENT);
        order.verify(staffDirectory).findStaffForUpdate(STAFF);
        order.verify(assignments).findByIdForUpdate(ASSIGNMENT);
        order.verify(requests).findPendingByAssignmentIdForUpdate(ASSIGNMENT);
        order.verify(requests).saveAndFlush(any(ShiftRequest.class));
        order.verify(staffDirectory).findActiveStaffIds(BRANCH, Role.BRANCH_MANAGER);
        order.verify(notifications, times(2)).enqueue(any(NotificationRequest.class));
        order.verify(mapper).toResponse(any(ShiftRequest.class), any(), any(), any(), any());
    }

    @Test
    void createLeaveSavesAPendingRequestOfTheCallerAndReturnsTheFifteenFields() {
        ShiftRequestResponse response = service.createShiftRequest(leave());

        ArgumentCaptor<ShiftRequest> saved = ArgumentCaptor.forClass(ShiftRequest.class);
        verify(requests).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getRequesterId()).isEqualTo(STAFF);
        assertThat(saved.getValue().getStatus()).isEqualTo(ShiftRequestStatus.PENDING);
        assertThat(saved.getValue().getTargetShiftDefinitionId()).isNull();
        assertThat(response.shiftRequestId()).isEqualTo(REQUEST);
        assertThat(response.shiftAssignmentId()).isEqualTo(ASSIGNMENT);
        assertThat(response.requesterId()).isEqualTo(STAFF);
        assertThat(response.requesterName()).isEqualTo("Nhân viên 12");
        assertThat(response.workDate()).isEqualTo(WORK_DAY);
        assertThat(response.currentShiftName()).isEqualTo("Ca sáng");
        assertThat(response.type()).isEqualTo(ShiftRequestType.LEAVE);
        assertThat(response.targetShiftDefinitionId()).isNull();
        assertThat(response.targetShiftName()).isNull();
        assertThat(response.reason()).isEqualTo("Việc gia đình");
        assertThat(response.status()).isEqualTo(ShiftRequestStatus.PENDING);
        assertThat(response.decidedBy()).isNull();
        assertThat(response.closedAt()).isNull();
    }

    @Test
    void createSwapKeepsTheTargetAndTheNoticeSaysDoiWhileLeaveSaysNghi() {
        ShiftRequestResponse swap = service.createShiftRequest(swap(AFTERNOON));

        assertThat(swap.type()).isEqualTo(ShiftRequestType.SWAP);
        assertThat(swap.targetShiftDefinitionId()).isEqualTo(AFTERNOON);
        assertThat(swap.targetShiftName()).isEqualTo("Ca chiều");
        ArgumentCaptor<NotificationRequest> swapNotice = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notifications, times(2)).enqueue(swapNotice.capture());
        assertThat(swapNotice.getAllValues()).allSatisfy(
                notice -> assertThat(notice.payload()).containsEntry("loai_yeu_cau", "đổi"));
    }

    @Test
    void everyActiveBranchManagerGetsOneInAppNoticeWithTheStrippedReasonAndNobodyElse() {
        service.createShiftRequest(new CreateShiftRequestRequest(ASSIGNMENT, ShiftRequestType.LEAVE, null,
                "  Việc gia đình  "));

        ArgumentCaptor<NotificationRequest> notices = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notifications, times(2)).enqueue(notices.capture());
        assertThat(notices.getAllValues()).extracting(NotificationRequest::recipientAccountId)
                .containsExactly(90L, 91L);
        assertThat(notices.getAllValues()).allSatisfy(notice -> {
            assertThat(notice.templateCode()).isEqualTo(NotificationTemplateCode.SHIFT_REQUEST_SUBMITTED_APP);
            assertThat(notice.channel()).isEqualTo(NotificationApi.Channel.IN_APP);
            assertThat(notice.recipientEmail()).isNull();
            assertThat(notice.payload()).containsEntry("ly_do", "Việc gia đình").containsEntry("loai_yeu_cau", "nghỉ")
                    .containsEntry("ten_ca", "Ca sáng").containsEntry("ngay_lam", "24/11/2026")
                    .containsEntry("chi_nhanh", "Chi nhánh Quận 1").containsEntry("ten_nhan_vien", "Nhân viên 12");
        });
    }

    @Test
    void withoutAnActiveBranchManagerTheSuperManagersAreNotifiedInstead() {
        when(staffDirectory.findActiveStaffIds(BRANCH, Role.BRANCH_MANAGER)).thenReturn(List.of());
        when(staffDirectory.findActiveSuperManagerIds()).thenReturn(List.of(77L));

        service.createShiftRequest(leave());

        ArgumentCaptor<NotificationRequest> notice = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notifications).enqueue(notice.capture());
        assertThat(notice.getValue().recipientAccountId()).isEqualTo(77L);
    }

    @Test
    void withNobodyToNotifyTheRequestIsStillSavedAndNothingIsEnqueued() {
        when(staffDirectory.findActiveStaffIds(BRANCH, Role.BRANCH_MANAGER)).thenReturn(List.of());
        when(staffDirectory.findActiveSuperManagerIds()).thenReturn(List.of());

        ShiftRequestResponse response = service.createShiftRequest(leave());

        assertThat(response.status()).isEqualTo(ShiftRequestStatus.PENDING);
        verify(requests).saveAndFlush(any(ShiftRequest.class));
        verifyNoInteractions(notifications);
    }

    // ---------------------------------------------------------------- #9: 404 trước mọi khóa, không lộ phân ca người khác

    @Test
    void anUnknownAssignmentOrAColleaguesAssignmentIs404BeforeAnyLockAndNeverLocksAnotherAccount() {
        when(assignments.findScopeById(999L)).thenReturn(Optional.empty());
        when(assignments.findScopeById(40L)).thenReturn(Optional.of(scope(COLLEAGUE, BRANCH)));

        for (long id : new long[] {999L, 40L}) {
            assertThatThrownBy(() -> service.createShiftRequest(
                    new CreateShiftRequestRequest(id, ShiftRequestType.LEAVE, null, "Việc gia đình")))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        verify(staffDirectory, never()).findStaffForUpdate(anyLong());
        assertNothingWritten();
    }

    @Test
    void aTargetShiftThatDoesNotExistOrBelongsToAnotherBranchIs404BeforeAnyLock() {
        givenDefinition(50L, OTHER_BRANCH, true, "Ca lạ", LocalTime.of(13, 0), LocalTime.of(17, 0));
        when(definitions.findById(51L)).thenReturn(Optional.empty());

        for (long target : new long[] {50L, 51L}) {
            assertThatThrownBy(() -> service.createShiftRequest(swap(target)))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        verify(staffDirectory, never()).findStaffForUpdate(anyLong());
        assertNothingWritten();
    }

    // ---------------------------------------------------------------- #9: BR-NS-04, mỗi nhánh không ghi gì

    @Test
    void aSwapWithoutATargetAndALeaveWithATargetAreBrNs04BeforeAnyLock() {
        assertBrNs04(() -> service.createShiftRequest(new CreateShiftRequestRequest(ASSIGNMENT,
                ShiftRequestType.SWAP, null, "Việc gia đình")), "phải chọn ca đích");
        assertBrNs04(() -> service.createShiftRequest(new CreateShiftRequestRequest(ASSIGNMENT,
                ShiftRequestType.LEAVE, AFTERNOON, "Việc gia đình")), "không có ca đích");

        verify(staffDirectory, never()).findStaffForUpdate(anyLong());
        assertNothingWritten();
    }

    @Test
    void anAssignmentThatIsNoLongerScheduledIs400NotAStateConflict() {
        assignment.cancel("Quản lý hủy", 99L, Instant.parse("2026-11-17T01:00:00Z"));

        assertBrNs04(() -> service.createShiftRequest(leave()), "phân ca đang chờ");
        assertNothingWritten();
    }

    @Test
    void theLeadTimeIsAtLeastTheConfiguredHoursSoExactlyThatManyHoursIsAcceptedAndOneMinuteLessIsNot() {
        // "Bây giờ" là 10:00 ngày 17/11; ca bắt đầu 10:00 ngày 18/11 cách đúng 24 giờ.
        givenAssignment(LocalDate.of(2026, 11, 18), MORNING);
        givenDefinition(MORNING, BRANCH, true, "Ca sáng", LocalTime.of(10, 0), LocalTime.of(12, 0));
        assertThat(service.createShiftRequest(leave()).status()).isEqualTo(ShiftRequestStatus.PENDING);

        givenDefinition(MORNING, BRANCH, true, "Ca sáng", LocalTime.of(9, 59), LocalTime.of(12, 0));
        assertBrNs04(() -> service.createShiftRequest(leave()), "ít nhất 24 giờ");
        verify(requests, times(1)).saveAndFlush(any(ShiftRequest.class));
    }

    @Test
    void theLeadTimeIsReadFromTheConfigurationOnEveryCall() {
        when(configs.getInt(ConfigKey.WORKFORCE_REQUEST_LEAD_HOURS)).thenReturn(200);

        assertBrNs04(() -> service.createShiftRequest(leave()), "ít nhất 200 giờ");
        assertNothingWritten();
    }

    @Test
    void anAssignmentThatAlreadyHasAPendingRequestIs400() {
        when(requests.findPendingByAssignmentIdForUpdate(ASSIGNMENT)).thenReturn(Optional.of(pending()));

        assertBrNs04(() -> service.createShiftRequest(leave()), "đã có yêu cầu đang chờ");
        assertNothingWritten();
    }

    @Test
    void aTargetThatIsInactiveOrTheSameAsTheCurrentShiftIs400() {
        givenDefinition(AFTERNOON, BRANCH, false, "Ca chiều", LocalTime.of(13, 0), LocalTime.of(17, 0));
        assertBrNs04(() -> service.createShiftRequest(swap(AFTERNOON)), "đã ngừng dùng");
        assertBrNs04(() -> service.createShiftRequest(swap(MORNING)), "trùng với ca hiện tại");

        assertNothingWritten();
    }

    @Test
    void aTargetOverlappingAnotherAssignmentOfTheDayIs400AndNamesIt() {
        when(assignments.findOverlapping(STAFF, WORK_DAY, LocalTime.of(13, 0), LocalTime.of(17, 0)))
                .thenReturn(List.of(overlap(77L, "Ca tối", LocalTime.of(16, 0), LocalTime.of(20, 0))));

        assertBrNs04(() -> service.createShiftRequest(swap(AFTERNOON)), "Ca tối (16:00–20:00)");
        assertNothingWritten();
    }

    @Test
    void theAssignmentBeingSwappedNeverCountsAsAnOverlapOfItsOwnTarget() {
        when(assignments.findOverlapping(STAFF, WORK_DAY, LocalTime.of(13, 0), LocalTime.of(17, 0)))
                .thenReturn(List.of(overlap(ASSIGNMENT, "Ca sáng", LocalTime.of(8, 0), LocalTime.of(14, 0))));

        assertThat(service.createShiftRequest(swap(AFTERNOON)).type()).isEqualTo(ShiftRequestType.SWAP);
    }

    // ---------------------------------------------------------------- #11 rút

    @Test
    void withdrawReadsTheOwnerWithoutLockingThenLocksAccountsThenAssignmentThenRequestThenWrites() {
        ShiftRequest pending = givenPendingRequest();

        ShiftRequestResponse response = service.withdrawShiftRequest(REQUEST);

        InOrder order = inOrder(requests, staffDirectory, assignments, mapper);
        order.verify(requests).findOwnerById(REQUEST);
        order.verify(staffDirectory).findStaffForUpdate(STAFF);
        order.verify(assignments).findByIdForUpdate(ASSIGNMENT);
        order.verify(requests).findByIdForUpdate(REQUEST);
        order.verify(requests).saveAndFlush(pending);
        order.verify(mapper).toResponse(any(ShiftRequest.class), any(), any(), any(), any());
        assertThat(response.status()).isEqualTo(ShiftRequestStatus.WITHDRAWN);
        assertThat(response.closedAt()).isEqualTo(CLOCK.instant());
        assertThat(response.decidedBy()).isNull();
        verifyNoInteractions(notifications);
    }

    @Test
    void withdrawingAColleaguesOrAnUnknownRequestIs404WithoutLockingAnything() {
        when(requests.findOwnerById(REQUEST)).thenReturn(Optional.of(owner(COLLEAGUE, ASSIGNMENT)));
        when(requests.findOwnerById(404L)).thenReturn(Optional.empty());

        for (long id : new long[] {REQUEST, 404L}) {
            assertThatThrownBy(() -> service.withdrawShiftRequest(id)).isInstanceOf(ResourceNotFoundException.class);
        }

        verify(staffDirectory, never()).findStaffForUpdate(anyLong());
        verify(requests, never()).findByIdForUpdate(anyLong());
        verify(requests, never()).saveAndFlush(any(ShiftRequest.class));
    }

    @Test
    void withdrawingARequestThatIsNoLongerPendingIs409AndWritesNothing() {
        ShiftRequest closed = givenPendingRequest();
        closed.rejectBySystem("Phân ca đã bị quản lý hủy", CLOCK.instant());

        assertThatThrownBy(() -> service.withdrawShiftRequest(REQUEST))
                .isInstanceOf(InvalidStateTransitionException.class);

        verify(requests, never()).saveAndFlush(any(ShiftRequest.class));
    }

    // ---------------------------------------------------------------- #10 danh sách

    @Test
    void listMergesEveryLookupOncePerPageNoMatterHowManyRows() {
        ShiftRequest first = givenListedRequest(1L, 31L, null);
        ShiftRequest second = givenListedRequest(2L, 32L, AFTERNOON);
        when(requests.findByRequester(eq(STAFF), eq(null), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(second, first)));
        when(assignments.findAllById(anyCollection())).thenReturn(List.of(assignmentRow(31L, MORNING),
                assignmentRow(32L, MORNING)));
        when(definitions.findAllById(anyCollection())).thenReturn(List.of(
                definition(MORNING, BRANCH, true, "Ca sáng", LocalTime.of(8, 0), LocalTime.of(12, 0)),
                definition(AFTERNOON, BRANCH, true, "Ca chiều", LocalTime.of(13, 0), LocalTime.of(17, 0))));

        PageResponse<ShiftRequestResponse> page = service.listMyShiftRequests(null, 0, 20);

        assertThat(page.content()).extracting(ShiftRequestResponse::shiftRequestId).containsExactly(2L, 1L);
        assertThat(page.content().get(0).targetShiftName()).isEqualTo("Ca chiều");
        assertThat(page.content().get(1).targetShiftName()).isNull();
        verify(staffDirectory, times(1)).findStaffByIds(anyCollection());
        verify(assignments, times(1)).findAllById(anyCollection());
        verify(definitions, times(1)).findAllById(anyCollection());
    }

    @Test
    void listClampsThePageAndTheSizeAndAlwaysFiltersByTheCaller() {
        when(requests.findByRequester(anyLong(), any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        service.listMyShiftRequests(ShiftRequestStatus.PENDING, -3, 1000);
        service.listMyShiftRequests(null, 2, 0);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(requests).findByRequester(eq(STAFF), eq(ShiftRequestStatus.PENDING), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isZero();
        assertThat(pageable.getValue().getPageSize()).isEqualTo(100);
        verify(requests).findByRequester(eq(STAFF), eq(null), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(1);
    }

    @Test
    void aListedRequestWhoseShiftIsMissingIsCorruptDataNotANullInTheResponse() {
        ShiftRequest row = givenListedRequest(1L, 31L, null);
        when(requests.findByRequester(anyLong(), any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(row)));
        when(assignments.findAllById(anyCollection())).thenReturn(List.of(assignmentRow(31L, MORNING)));
        when(definitions.findAllById(anyCollection())).thenReturn(List.of());

        assertThatThrownBy(() -> service.listMyShiftRequests(null, 0, 20)).isInstanceOf(IllegalStateException.class);
    }

    // ---------------------------------------------------------------- cấu hình transaction

    @Test
    void createAndWithdrawAreWriteTransactionsBecauseTheStaffLockRejectsReadOnlyAndNothingOverridesRollback()
            throws Exception {
        for (Transactional tx : List.of(
                ShiftRequestService.class.getMethod("createShiftRequest", CreateShiftRequestRequest.class)
                        .getAnnotation(Transactional.class),
                ShiftRequestService.class.getMethod("withdrawShiftRequest", Long.class)
                        .getAnnotation(Transactional.class))) {
            assertThat(tx).isNotNull();
            assertThat(tx.readOnly()).isFalse();
            assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRED);
            assertThat(tx.isolation()).isEqualTo(Isolation.DEFAULT);
            assertThat(tx.rollbackFor()).isEmpty();
            assertThat(tx.noRollbackFor()).isEmpty();
        }
    }

    @Test
    void listIsReadOnlyRepeatableRead() throws Exception {
        Transactional tx = ShiftRequestService.class.getMethod("listMyShiftRequests", ShiftRequestStatus.class,
                int.class, int.class).getAnnotation(Transactional.class);

        assertThat(tx.readOnly()).isTrue();
        assertThat(tx.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
    }

    // ---------------------------------------------------------------- hạ tầng

    private void assertNothingWritten() {
        verify(requests, never()).saveAndFlush(any(ShiftRequest.class));
        verifyNoInteractions(notifications);
    }

    private static void assertBrNs04(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String fragment) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessRuleViolationException.class, e -> {
            assertThat(e.getRuleId()).isEqualTo("BR-NS-04");
            assertThat(e.getMessage()).contains(fragment).endsWith("(BR-NS-04)");
        });
    }

    private static CreateShiftRequestRequest leave() {
        return new CreateShiftRequestRequest(ASSIGNMENT, ShiftRequestType.LEAVE, null, "Việc gia đình");
    }

    private static CreateShiftRequestRequest swap(long target) {
        return new CreateShiftRequestRequest(ASSIGNMENT, ShiftRequestType.SWAP, target, "Việc gia đình");
    }

    private ShiftAssignment givenAssignment(LocalDate day, long definitionId) {
        ShiftAssignment row = ShiftAssignment.schedule(BRANCH, STAFF, definitionId, day, ShiftAssignmentSource.SELF);
        ReflectionTestUtils.setField(row, "id", ASSIGNMENT);
        when(assignments.findScopeById(ASSIGNMENT)).thenReturn(Optional.of(scope(STAFF, BRANCH)));
        when(assignments.findByIdForUpdate(ASSIGNMENT)).thenReturn(Optional.of(row));
        when(requests.findPendingByAssignmentIdForUpdate(ASSIGNMENT)).thenReturn(Optional.empty());
        when(assignments.findAllById(anyCollection())).thenReturn(List.of(row));
        this.assignment = row;
        return row;
    }

    private void givenDefinition(long id, long branchId, boolean active, String name, LocalTime start,
            LocalTime end) {
        ShiftDefinition definition = definition(id, branchId, active, name, start, end);
        when(definitions.findById(id)).thenReturn(Optional.of(definition));
        List<ShiftDefinition> all = List.of(definition(MORNING, BRANCH, true, "Ca sáng", LocalTime.of(8, 0),
                LocalTime.of(12, 0)), definition(AFTERNOON, BRANCH, true, "Ca chiều", LocalTime.of(13, 0),
                        LocalTime.of(17, 0)));
        when(definitions.findAllById(anyCollection())).thenReturn(
                all.stream().map(d -> d.getId().equals(id) ? definition : d).toList());
    }

    private ShiftRequest givenPendingRequest() {
        ShiftRequest request = pending();
        when(requests.findOwnerById(REQUEST)).thenReturn(Optional.of(owner(STAFF, ASSIGNMENT)));
        when(requests.findByIdForUpdate(REQUEST)).thenReturn(Optional.of(request));
        return request;
    }

    private ShiftRequest givenListedRequest(long id, long assignmentId, Long target) {
        ShiftRequest request = ShiftRequest.submit(assignmentId, STAFF, MORNING,
                target == null ? ShiftRequestType.LEAVE : ShiftRequestType.SWAP, target, "Việc gia đình");
        ReflectionTestUtils.setField(request, "id", id);
        return request;
    }

    private static ShiftRequest pending() {
        ShiftRequest request = ShiftRequest.submit(ASSIGNMENT, STAFF, MORNING, ShiftRequestType.LEAVE, null,
                "Việc gia đình");
        ReflectionTestUtils.setField(request, "id", REQUEST);
        return request;
    }

    private static ShiftAssignment assignmentRow(long id, long definitionId) {
        ShiftAssignment row = ShiftAssignment.schedule(BRANCH, STAFF, definitionId, WORK_DAY,
                ShiftAssignmentSource.SELF);
        ReflectionTestUtils.setField(row, "id", id);
        return row;
    }

    private static StaffSummary staff(long id) {
        return new StaffSummary(id, "Nhân viên " + id, Role.RECEPTIONIST, BRANCH, true, false);
    }

    private static ShiftDefinition definition(long id, long branchId, boolean active, String name, LocalTime start,
            LocalTime end) {
        ShiftDefinition definition = ShiftDefinition.create(branchId, name, start, end);
        ReflectionTestUtils.setField(definition, "id", id);
        if (!active) {
            definition.deactivate();
        }
        return definition;
    }

    /** Lớp ẩn danh, không dùng mock: tạo mock bên trong {@code thenReturn(...)} là stub lồng nhau (bẫy Mockito). */
    private static AssignmentScope scope(long staffId, long branchId) {
        return new AssignmentScope() {
            @Override
            public Long getStaffId() {
                return staffId;
            }

            @Override
            public Long getBranchId() {
                return branchId;
            }
        };
    }

    private static RequestOwner owner(long requesterId, long assignmentId) {
        return new RequestOwner() {
            @Override
            public Long getRequesterId() {
                return requesterId;
            }

            @Override
            public Long getAssignmentId() {
                return assignmentId;
            }
        };
    }

    private static OverlappingShift overlap(long assignmentId, String name, LocalTime start, LocalTime end) {
        return new OverlappingShift() {
            @Override
            public Long getAssignmentId() {
                return assignmentId;
            }

            @Override
            public String getShiftName() {
                return name;
            }

            @Override
            public LocalTime getStartTime() {
                return start;
            }

            @Override
            public LocalTime getEndTime() {
                return end;
            }
        };
    }
}
