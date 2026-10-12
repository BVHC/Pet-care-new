package com.petcare.module.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.branch.api.BranchApi;
import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.branch.api.BranchQueryApi.BranchStatus;
import com.petcare.module.branch.api.BranchQueryApi.BranchSummary;
import com.petcare.module.care.api.NotificationApi;
import com.petcare.module.care.api.NotificationApi.Channel;
import com.petcare.module.care.api.NotificationApi.NotificationRequest;
import com.petcare.module.identity.api.NotificationTemplateCode;
import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.dto.ReactivateStaffRequest;
import com.petcare.module.identity.dto.StaffResponse;
import com.petcare.module.identity.entity.Account;
import com.petcare.module.identity.entity.AccountStatus;
import com.petcare.module.identity.entity.StaffProfile;
import com.petcare.module.identity.exception.StaffHierarchyDeniedException;
import com.petcare.module.identity.fsm.AccountTransitionHandler;
import com.petcare.module.identity.mapper.StaffAdminMapper;
import com.petcare.module.identity.repository.AccountRepository;
import com.petcare.module.identity.repository.StaffDetailRow;
import com.petcare.module.identity.repository.StaffProfileRepository;
import com.petcare.module.identity.repository.StaffRow;
import com.petcare.module.identity.service.TemporaryPasswordGenerator.TemporaryPassword;
import com.petcare.module.sales.api.CashierShiftQueryApi;
import com.petcare.module.visit.api.VisitQueryApi;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ConcurrencyConflictException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;

/**
 * UC08 — vô hiệu hóa (#20, Tài khoản#5), kích hoạt lại (#21, Tài khoản#6) (docs/adr/0033).
 * <ul>
 *   <li>#20: đọc không khóa (404) → khóa {@code branches} → khóa {@code accounts} → hồ sơ → phân cấp (403) →
 *       BR-QT-12 → [chỉ ACTIVE, A05–A08: chi nhánh vừa đổi 409 → BR-QT-04 → BR-QT-08] → {@code validateTransition}
 *       (409) → ghi → hủy phiên → audit → flush → đọc response.</li>
 *   <li>#21: khóa {@code accounts} (404) → hồ sơ → phân cấp (403) → BR-QT-10 → BR-QT-03 → {@code validateTransition}
 *       (409) → ghi → hủy phiên → outbox → audit → flush → đọc response. Không khóa {@code branches}.</li>
 * </ul>
 * Mỗi nhánh lỗi không gọi lệnh ghi nào; nhánh lỗi đứng trước BR-QT-08, người đã {@code DISABLED} và SUPER_MANAGER không
 * gọi hai interface placeholder (nợ D016). Policy, FSM và mapper thật.
 */
class StaffDisableReactivateServiceTest {

    private static final long ACTOR = 1L;
    private static final long TARGET = 7L;
    private static final long ACTIVE_BRANCH = 3L;
    private static final long DRAFT_BRANCH = 4L;
    private static final long OTHER_ACTIVE_BRANCH = 5L;
    private static final long UNKNOWN_BRANCH = 99L;
    private static final Instant NOW = Instant.parse("2026-10-12T03:00:00Z");
    private static final Map<Long, BranchStatus> BRANCHES = Map.of(ACTIVE_BRANCH, BranchStatus.ACTIVE,
            DRAFT_BRANCH, BranchStatus.DRAFT, OTHER_ACTIVE_BRANCH, BranchStatus.ACTIVE);
    private static final TemporaryPassword PASSWORD = new TemporaryPassword("Tam12345abcd", "new-hash");

    private final AccountRepository accounts = mock(AccountRepository.class);
    private final StaffProfileRepository staffProfiles = mock(StaffProfileRepository.class);
    private final BranchScope branchScope = mock(BranchScope.class);
    private final BranchQueryApi branches = mock(BranchQueryApi.class);
    private final SessionService sessions = mock(SessionService.class);
    private final NotificationApi notifications = mock(NotificationApi.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);
    private final StaffPresence presence = mock(StaffPresence.class);
    private final StaffAdminMapper mapper = Mappers.getMapper(StaffAdminMapper.class);
    private final BranchApi branchLocks = mock(BranchApi.class);
    private final VisitQueryApi visitQueries = mock(VisitQueryApi.class);
    private final CashierShiftQueryApi cashierShifts = mock(CashierShiftQueryApi.class);
    private final StaffAccountService service = new StaffAccountService(accounts, staffProfiles,
            new AccountTransitionHandler(), new StaffHierarchyPolicy(), branchScope, branches, sessions, notifications,
            audit, mapper, presence, branchLocks, visitQueries, cashierShifts);

    @BeforeEach
    void setUp() {
        when(presence.onlineSince()).thenReturn(NOW.minusSeconds(600));
        when(branchLocks.lockForStaffChange(any())).thenAnswer(invocation -> {
            Collection<Long> ids = invocation.getArgument(0);
            SortedMap<Long, BranchStatus> locked = new TreeMap<>();
            ids.stream().filter(BRANCHES::containsKey).forEach(id -> locked.put(id, BRANCHES.get(id)));
            return locked;
        });
        BRANCHES.forEach((id, status) -> when(branches.findBranch(id)).thenReturn(Optional.of(
                new BranchSummary(id, "CN " + id, "Địa chỉ", "0280000000", status, false))));
        when(branches.findBranch(UNKNOWN_BRANCH)).thenReturn(Optional.empty());
        when(visitQueries.findUnfinishedVisitIdsAssignedTo(anyLong())).thenReturn(List.of());
        actAs(Role.SUPER_MANAGER, null);
    }

    // ================================================================ #20 vô hiệu hóa

    @Test
    void disableLocksBranchThenAccountThenGuardsThenWritesRevokesAuditsFlushesAndReadsBack() {
        Target target = target(Role.VET, AccountStatus.ACTIVE, ACTIVE_BRANCH);
        ReflectionTestUtils.setField(target.account(), "lastSeenAt", NOW);
        StaffDetailRow after = givenDetail(AccountStatus.DISABLED, ACTIVE_BRANCH);

        StaffResponse response = service.disableStaff(TARGET);

        assertThat(response).isEqualTo(mapper.toResponse(after, false));
        InOrder order = inOrder(staffProfiles, branchLocks, accounts, visitQueries, cashierShifts, sessions, audit);
        order.verify(staffProfiles).findStaffRow(TARGET);
        order.verify(branchLocks).lockForStaffChange(Set.of(ACTIVE_BRANCH));
        order.verify(accounts).findByIdForUpdate(TARGET);
        order.verify(staffProfiles).findById(TARGET);
        order.verify(visitQueries).findUnfinishedVisitIdsAssignedTo(TARGET);
        order.verify(cashierShifts).hasOpenShift(TARGET);
        order.verify(sessions).revokeAll(TARGET);
        order.verify(audit).record(any());
        order.verify(staffProfiles).flush();
        order.verify(staffProfiles).findStaffDetail(TARGET);
        assertThat(target.account().getStatus()).isEqualTo(AccountStatus.DISABLED);
        assertThat(target.account().getLastSeenAt()).isNull();
        assertThat(target.profile().getBranchId()).isEqualTo(ACTIVE_BRANCH);

        AuditEntry entry = capturedAudit();
        assertThat(entry.action()).isEqualTo(QtAuditActions.STAFF_DISABLED);
        assertThat(entry.entityType()).isEqualTo("accounts");
        assertThat(entry.entityId()).isEqualTo(TARGET);
        assertThat(snapshot(entry.before())).containsExactly(Map.entry("status", "ACTIVE"));
        assertThat(snapshot(entry.after())).containsExactly(Map.entry("status", "DISABLED"));
        verifyNoInteractions(notifications);
    }

    /** Q2 (docs/adr/0033): SUPER_MANAGER không có chi nhánh, không bao giờ có lượt / ca → không hỏi hai placeholder. */
    @Test
    void adminDisablesSuperManagerWithoutBranchLockOrPendingWorkCheck() {
        actAs(Role.ADMIN, null);
        Target target = target(Role.SUPER_MANAGER, AccountStatus.ACTIVE, null);
        givenDetail(AccountStatus.DISABLED, null);

        service.disableStaff(TARGET);

        verify(branchLocks).lockForStaffChange(Set.of());
        assertThat(target.account().getStatus()).isEqualTo(AccountStatus.DISABLED);
        verify(sessions).revokeAll(TARGET);
        verify(staffProfiles, never()).countActiveBranchManagers(anyLong());
        verifyNoInteractions(visitQueries, cashierShifts);
    }

    @Test
    void branchManagerDisablesOwnSubordinate() {
        actAs(Role.BRANCH_MANAGER, ACTIVE_BRANCH);
        Target target = target(Role.CARETAKER, AccountStatus.ACTIVE, ACTIVE_BRANCH);
        givenDetail(AccountStatus.DISABLED, ACTIVE_BRANCH);

        service.disableStaff(TARGET);

        assertThat(target.account().getStatus()).isEqualTo(AccountStatus.DISABLED);
    }

    /** BR-QT-12: kể cả người đã DISABLED đang bị khóa — 400 đứng trước 409 (00-method L70). */
    @ParameterizedTest(name = "status={0}")
    @CsvSource({"ACTIVE", "DISABLED"})
    void lockedTargetIsBrQt12BeforeAnyOtherGuard(AccountStatus status) {
        Target target = target(Role.BRANCH_MANAGER, status, ACTIVE_BRANCH);
        ReflectionTestUtils.setField(target.account(), "locked", true);
        when(staffProfiles.countActiveBranchManagers(ACTIVE_BRANCH)).thenReturn(1L);

        assertRule(() -> service.disableStaff(TARGET), "BR-QT-12",
                "Tài khoản đang bị khóa, cần mở khóa trước khi vô hiệu hóa (BR-QT-12)");
        assertThat(target.account().getStatus()).isEqualTo(status);
        verify(staffProfiles, never()).countActiveBranchManagers(anyLong());
        verifyNoInteractions(visitQueries, cashierShifts);
        assertNoWrite();
    }

    /** Q1: đã DISABLED → 409 từ FSM; không đếm, không hỏi placeholder (nếu không, bấm đúp sẽ 500 tới 21/10). */
    @Test
    void alreadyDisabledIs409FromFsmWithoutCountOrPendingWorkCheck() {
        Target target = target(Role.BRANCH_MANAGER, AccountStatus.DISABLED, ACTIVE_BRANCH);
        when(staffProfiles.countActiveBranchManagers(ACTIVE_BRANCH)).thenReturn(0L);

        assertThatThrownBy(() -> service.disableStaff(TARGET)).isInstanceOf(InvalidStateTransitionException.class)
                .hasMessage("Không thể chuyển trạng thái tài khoản từ DISABLED sang DISABLED");
        assertThat(target.account().getStatus()).isEqualTo(AccountStatus.DISABLED);
        verify(staffProfiles, never()).countActiveBranchManagers(anyLong());
        verifyNoInteractions(visitQueries, cashierShifts);
        assertNoWrite();
    }

    /** BR-QT-04 (đếm tính cả người bị khóa, docs/adr/0024); chỉ chặn quản lý ACTIVE cuối cùng của chi nhánh ACTIVE. */
    @ParameterizedTest(name = "role={0} branch={1} count={2} → rejected={3}")
    @CsvSource({
            "BRANCH_MANAGER, ACTIVE, 1, true",
            "BRANCH_MANAGER, ACTIVE, 2, false",
            "BRANCH_MANAGER, DRAFT,  1, false",
            "VET,            ACTIVE, 1, false"})
    void lastManagerTruthTableForDisable(Role role, String branch, long count, boolean rejected) {
        long branchId = "ACTIVE".equals(branch) ? ACTIVE_BRANCH : DRAFT_BRANCH;
        Target target = target(role, AccountStatus.ACTIVE, branchId);
        when(staffProfiles.countActiveBranchManagers(branchId)).thenReturn(count);
        givenDetail(AccountStatus.DISABLED, branchId);

        if (rejected) {
            assertRule(() -> service.disableStaff(TARGET), "BR-QT-04",
                    "Không vô hiệu hóa được: đây là quản lý cuối cùng của chi nhánh đang hoạt động (BR-QT-04)");
            assertThat(target.account().getStatus()).isEqualTo(AccountStatus.ACTIVE);
            verifyNoInteractions(visitQueries, cashierShifts);
            assertNoWrite();
        } else {
            service.disableStaff(TARGET);
            assertThat(target.account().getStatus()).isEqualTo(AccountStatus.DISABLED);
        }
    }

    @Test
    void branchMovedWhileWaitingIs409BeforeCounting() {
        Target target = target(Role.BRANCH_MANAGER, AccountStatus.ACTIVE, ACTIVE_BRANCH);
        ReflectionTestUtils.setField(target.profile(), "branchId", OTHER_ACTIVE_BRANCH);

        assertThatThrownBy(() -> service.disableStaff(TARGET)).isInstanceOf(ConcurrencyConflictException.class);
        assertThat(target.account().getStatus()).isEqualTo(AccountStatus.ACTIVE);
        verify(staffProfiles, never()).countActiveBranchManagers(anyLong());
        verifyNoInteractions(visitQueries, cashierShifts);
        assertNoWrite();
    }

    @ParameterizedTest(name = "visits={0} shift={1}")
    @CsvSource(delimiter = '|', value = {
            "12,15 | false | Không vô hiệu hóa được: còn lượt tiếp nhận #12, #15 chưa hoàn tất (BR-QT-08)",
            "      | true  | Không vô hiệu hóa được: còn ca thu ngân đang mở (BR-QT-08)",
            "12    | true  | Không vô hiệu hóa được: còn lượt tiếp nhận #12 chưa hoàn tất; còn ca thu ngân đang mở (BR-QT-08)"})
    void pendingWorkIsBrQt08ListingEverything(String visitIds, boolean openShift, String message) {
        Target target = target(Role.RECEPTIONIST, AccountStatus.ACTIVE, ACTIVE_BRANCH);
        List<Long> visits = visitIds == null ? List.of()
                : Arrays.stream(visitIds.split(",")).map(Long::valueOf).toList();
        when(visitQueries.findUnfinishedVisitIdsAssignedTo(TARGET)).thenReturn(visits);
        when(cashierShifts.hasOpenShift(TARGET)).thenReturn(openShift);

        assertRule(() -> service.disableStaff(TARGET), "BR-QT-08", message);
        assertThat(target.account().getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertNoWrite();
    }

    @Test
    void placeholderGuardFailurePropagatesWithoutWrite() {
        Target target = target(Role.VET, AccountStatus.ACTIVE, ACTIVE_BRANCH);
        when(visitQueries.findUnfinishedVisitIdsAssignedTo(TARGET))
                .thenThrow(new UnsupportedOperationException("placeholder"));

        assertThatThrownBy(() -> service.disableStaff(TARGET)).isInstanceOf(UnsupportedOperationException.class);
        assertThat(target.account().getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertNoWrite();
    }

    @Test
    void disableMissingOrCustomerIs404BeforeAnyLock() {
        when(staffProfiles.findStaffRow(8L)).thenReturn(Optional.empty());
        when(staffProfiles.findStaffRow(9L)).thenReturn(Optional.of(new StaffRow(9L, "Khách", Role.CUSTOMER, null,
                AccountStatus.ACTIVE, false, null)));

        assertThatThrownBy(() -> service.disableStaff(8L)).isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Không tìm thấy nhân viên #8");
        assertThatThrownBy(() -> service.disableStaff(9L)).isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Không tìm thấy nhân viên #9");
        verifyNoInteractions(branchLocks);
        verify(accounts, never()).findByIdForUpdate(anyLong());
        assertNoWrite();
    }

    /** BR-QT-07: BM không vô hiệu hóa BM / người chi nhánh khác; ADMIN chỉ SUPER_MANAGER; không ai tự vô hiệu hóa. */
    @Test
    void disableHierarchyDenialWritesNothingAndSkipsPendingWork() {
        actAs(Role.BRANCH_MANAGER, ACTIVE_BRANCH);
        target(Role.BRANCH_MANAGER, AccountStatus.ACTIVE, ACTIVE_BRANCH);
        assertThatThrownBy(() -> service.disableStaff(TARGET)).isInstanceOf(StaffHierarchyDeniedException.class);

        target(Role.VET, AccountStatus.ACTIVE, OTHER_ACTIVE_BRANCH);
        assertThatThrownBy(() -> service.disableStaff(TARGET)).isInstanceOf(StaffHierarchyDeniedException.class);

        actAs(Role.ADMIN, null);
        target(Role.VET, AccountStatus.ACTIVE, ACTIVE_BRANCH);
        assertThatThrownBy(() -> service.disableStaff(TARGET)).isInstanceOf(StaffHierarchyDeniedException.class);

        actAs(Role.SUPER_MANAGER, null);
        targetWithId(ACTOR, Role.SUPER_MANAGER, AccountStatus.ACTIVE, null);
        assertThatThrownBy(() -> service.disableStaff(ACTOR)).isInstanceOf(StaffHierarchyDeniedException.class);

        verify(staffProfiles, never()).countActiveBranchManagers(anyLong());
        verifyNoInteractions(visitQueries, cashierShifts);
        assertNoWrite();
    }

    @Test
    void disableBranchStaffWithoutBranchIsBadData() {
        target(Role.VET, AccountStatus.ACTIVE, null);

        assertThatThrownBy(() -> service.disableStaff(TARGET)).isInstanceOf(IllegalStateException.class);
        assertNoWrite();
    }

    // ================================================================ #21 kích hoạt lại

    @Test
    void reactivateLocksAccountOnlyThenWritesRevokesSendsPasswordAuditsFlushesAndReadsBack() {
        Target target = target(Role.VET, AccountStatus.DISABLED, ACTIVE_BRANCH);
        ReflectionTestUtils.setField(target.account(), "failedLoginCount", 3);
        ReflectionTestUtils.setField(target.account(), "firstFailedLoginAt", NOW);
        ReflectionTestUtils.setField(target.account(), "lockedUntil", NOW.plusSeconds(60));
        ReflectionTestUtils.setField(target.account(), "mustChangePassword", false);
        StaffDetailRow after = givenDetail(AccountStatus.ACTIVE, ACTIVE_BRANCH);

        StaffResponse response = service.reactivateStaff(TARGET, null, PASSWORD);

        assertThat(response).isEqualTo(mapper.toResponse(after, false));
        InOrder order = inOrder(accounts, staffProfiles, sessions, notifications, audit);
        order.verify(accounts).findByIdForUpdate(TARGET);
        order.verify(staffProfiles).findById(TARGET);
        order.verify(sessions).revokeAll(TARGET);
        order.verify(notifications).enqueue(any());
        order.verify(audit).record(any());
        order.verify(staffProfiles).flush();
        order.verify(staffProfiles).findStaffDetail(TARGET);
        verifyNoInteractions(branchLocks, visitQueries, cashierShifts, branches);
        verify(staffProfiles, never()).findStaffRow(anyLong());

        Account account = target.account();
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.getPasswordHash()).isEqualTo("new-hash");
        assertThat(account.isMustChangePassword()).isTrue();
        assertThat(account.getFailedLoginCount()).isZero();
        assertThat(account.getFirstFailedLoginAt()).isNull();
        assertThat(account.getLockedUntil()).isNull();
        assertThat(account.getLastSeenAt()).isNull();
        assertThat(target.profile().getBranchId()).isEqualTo(ACTIVE_BRANCH);

        ArgumentCaptor<NotificationRequest> mail = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notifications).enqueue(mail.capture());
        assertThat(mail.getValue().templateCode()).isEqualTo(NotificationTemplateCode.STAFF_TEMP_PASSWORD);
        assertThat(mail.getValue().channel()).isEqualTo(Channel.EMAIL);
        assertThat(mail.getValue().recipientEmail()).isEqualTo("staff7@petcare.test");
        assertThat(mail.getValue().recipientAccountId()).isNull();
        assertThat(mail.getValue().payload()).containsOnly(Map.entry("ho_ten", "Nhân viên 7"),
                Map.entry("email_dang_nhap", "staff7@petcare.test"), Map.entry("mat_khau_tam", "Tam12345abcd"));

        AuditEntry entry = capturedAudit();
        assertThat(entry.action()).isEqualTo(QtAuditActions.STAFF_REACTIVATED);
        assertThat(entry.entityId()).isEqualTo(TARGET);
        assertThat(snapshot(entry.before())).containsExactly(Map.entry("status", "DISABLED"),
                Map.entry("branchId", ACTIVE_BRANCH));
        assertThat(snapshot(entry.after())).containsExactly(Map.entry("status", "ACTIVE"),
                Map.entry("branchId", ACTIVE_BRANCH));
    }

    @Test
    void reactivateWithNewBranchMovesStaffAndAuditsBothBranches() {
        Target target = target(Role.BRANCH_MANAGER, AccountStatus.DISABLED, ACTIVE_BRANCH);
        givenDetail(AccountStatus.ACTIVE, DRAFT_BRANCH);

        service.reactivateStaff(TARGET, new ReactivateStaffRequest(DRAFT_BRANCH), PASSWORD);

        verify(branches).findBranch(DRAFT_BRANCH);
        assertThat(target.profile().getBranchId()).isEqualTo(DRAFT_BRANCH);
        AuditEntry entry = capturedAudit();
        assertThat(snapshot(entry.before())).containsEntry("branchId", ACTIVE_BRANCH);
        assertThat(snapshot(entry.after())).containsEntry("branchId", DRAFT_BRANCH);
        verifyNoInteractions(branchLocks);
    }

    @Test
    void reactivateWithCurrentBranchOrEmptyBodyKeepsBranchWithoutLookup() {
        Target target = target(Role.VET, AccountStatus.DISABLED, ACTIVE_BRANCH);
        givenDetail(AccountStatus.ACTIVE, ACTIVE_BRANCH);

        service.reactivateStaff(TARGET, new ReactivateStaffRequest(ACTIVE_BRANCH), PASSWORD);

        assertThat(target.profile().getBranchId()).isEqualTo(ACTIVE_BRANCH);
        verifyNoInteractions(branches);

        Target second = target(Role.VET, AccountStatus.DISABLED, ACTIVE_BRANCH);
        service.reactivateStaff(TARGET, new ReactivateStaffRequest(null), PASSWORD);
        assertThat(second.profile().getBranchId()).isEqualTo(ACTIVE_BRANCH);
        verifyNoInteractions(branches);
    }

    @Test
    void adminReactivatesSuperManagerWithoutBranchButRejectsBranchId() {
        actAs(Role.ADMIN, null);
        Target target = target(Role.SUPER_MANAGER, AccountStatus.DISABLED, null);

        assertRule(() -> service.reactivateStaff(TARGET, new ReactivateStaffRequest(ACTIVE_BRANCH), PASSWORD),
                "BR-QT-03", "Quản lý cấp chuỗi không thuộc chi nhánh nào (BR-QT-03)");
        assertThat(target.account().getStatus()).isEqualTo(AccountStatus.DISABLED);
        assertNoWrite();

        givenDetail(AccountStatus.ACTIVE, null);
        service.reactivateStaff(TARGET, null, PASSWORD);
        assertThat(target.account().getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(target.profile().getBranchId()).isNull();
    }

    @Test
    void reactivateUnknownBranchIsBrQt03WithoutWrite() {
        Target target = target(Role.VET, AccountStatus.DISABLED, ACTIVE_BRANCH);

        assertRule(() -> service.reactivateStaff(TARGET, new ReactivateStaffRequest(UNKNOWN_BRANCH), PASSWORD),
                "BR-QT-03", "Chi nhánh không tồn tại (BR-QT-03)");
        assertThat(target.account().getStatus()).isEqualTo(AccountStatus.DISABLED);
        assertThat(target.profile().getBranchId()).isEqualTo(ACTIVE_BRANCH);
        assertNoWrite();
    }

    /** BR-QT-10 "LOCKED phải mở khóa trước" đứng trước BR-QT-03 và trước 409. */
    @ParameterizedTest(name = "status={0}")
    @CsvSource({"DISABLED", "ACTIVE"})
    void lockedTargetIsBrQt10BeforeBranchAndStateChecks(AccountStatus status) {
        Target target = target(Role.VET, status, ACTIVE_BRANCH);
        ReflectionTestUtils.setField(target.account(), "locked", true);

        assertRule(() -> service.reactivateStaff(TARGET, new ReactivateStaffRequest(UNKNOWN_BRANCH), PASSWORD),
                "BR-QT-10", "Tài khoản đang bị khóa, cần mở khóa trước (BR-QT-10)");
        assertThat(target.account().getStatus()).isEqualTo(status);
        verifyNoInteractions(branches);
        assertNoWrite();
    }

    /** Q1: đang ACTIVE → 409 (FSM), nhưng chi nhánh sai vẫn báo 400 trước (00-method L70: guard 400 → 409). */
    @Test
    void activeTargetIs409AfterBranchGuard() {
        Target target = target(Role.VET, AccountStatus.ACTIVE, ACTIVE_BRANCH);

        assertThatThrownBy(() -> service.reactivateStaff(TARGET, null, PASSWORD))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessage("Không thể chuyển trạng thái tài khoản từ ACTIVE sang ACTIVE");
        assertRule(() -> service.reactivateStaff(TARGET, new ReactivateStaffRequest(UNKNOWN_BRANCH), PASSWORD),
                "BR-QT-03", "Chi nhánh không tồn tại (BR-QT-03)");
        assertThat(target.account().getPasswordHash()).isEqualTo("hash");
        assertNoWrite();
    }

    @Test
    void reactivateMissingOrCustomerIs404() {
        when(accounts.findByIdForUpdate(8L)).thenReturn(Optional.empty());
        Account customer = Account.registerCustomer("khach@petcare.test", "hash", NOW);
        ReflectionTestUtils.setField(customer, "id", 9L);
        when(accounts.findByIdForUpdate(9L)).thenReturn(Optional.of(customer));

        assertThatThrownBy(() -> service.reactivateStaff(8L, null, PASSWORD))
                .isInstanceOf(ResourceNotFoundException.class).hasMessage("Không tìm thấy nhân viên #8");
        assertThatThrownBy(() -> service.reactivateStaff(9L, null, PASSWORD))
                .isInstanceOf(ResourceNotFoundException.class).hasMessage("Không tìm thấy nhân viên #9");
        verify(staffProfiles, never()).findById(anyLong());
        assertNoWrite();
    }

    /** BR-QT-10 cùng phạm vi với BR-QT-07; BRANCH_MANAGER không gán sang chi nhánh khác (docs/adr/0029 mục 1). */
    @Test
    void reactivateHierarchyDenialWritesNothing() {
        actAs(Role.BRANCH_MANAGER, ACTIVE_BRANCH);
        target(Role.VET, AccountStatus.DISABLED, ACTIVE_BRANCH);
        assertThatThrownBy(() -> service.reactivateStaff(TARGET, new ReactivateStaffRequest(OTHER_ACTIVE_BRANCH),
                PASSWORD)).isInstanceOf(StaffHierarchyDeniedException.class);

        target(Role.BRANCH_MANAGER, AccountStatus.DISABLED, ACTIVE_BRANCH);
        assertThatThrownBy(() -> service.reactivateStaff(TARGET, null, PASSWORD))
                .isInstanceOf(StaffHierarchyDeniedException.class);

        target(Role.VET, AccountStatus.DISABLED, OTHER_ACTIVE_BRANCH);
        assertThatThrownBy(() -> service.reactivateStaff(TARGET, null, PASSWORD))
                .isInstanceOf(StaffHierarchyDeniedException.class);

        actAs(Role.ADMIN, null);
        target(Role.VET, AccountStatus.DISABLED, ACTIVE_BRANCH);
        assertThatThrownBy(() -> service.reactivateStaff(TARGET, null, PASSWORD))
                .isInstanceOf(StaffHierarchyDeniedException.class);

        verifyNoInteractions(branches);
        assertNoWrite();
    }

    @Test
    void branchManagerReactivatesOwnSubordinateKeepingBranch() {
        actAs(Role.BRANCH_MANAGER, ACTIVE_BRANCH);
        Target target = target(Role.RECEPTIONIST, AccountStatus.DISABLED, ACTIVE_BRANCH);
        givenDetail(AccountStatus.ACTIVE, ACTIVE_BRANCH);

        service.reactivateStaff(TARGET, new ReactivateStaffRequest(ACTIVE_BRANCH), PASSWORD);

        assertThat(target.account().getStatus()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void reactivateBranchStaffWithoutBranchIsBadData() {
        target(Role.VET, AccountStatus.DISABLED, null);

        assertThatThrownBy(() -> service.reactivateStaff(TARGET, null, PASSWORD))
                .isInstanceOf(IllegalStateException.class);
        assertNoWrite();
    }

    // ================================================================ transaction

    /** Một transaction trọn use case, rollback với mọi lỗi (không có bộ đếm nào phải sống sót — docs/adr/0033). */
    @Test
    void bothUseCasesAreSingleWritableTransactionsRollingBackOnEveryException() throws Exception {
        for (Method method : List.of(
                StaffAccountService.class.getMethod("disableStaff", Long.class),
                StaffAccountService.class.getMethod("reactivateStaff", Long.class, ReactivateStaffRequest.class,
                        TemporaryPassword.class))) {
            Transactional tx = method.getAnnotation(Transactional.class);
            assertThat(tx).as(method.getName()).isNotNull();
            assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRED);
            assertThat(tx.isolation()).isEqualTo(Isolation.DEFAULT);
            assertThat(tx.readOnly()).isFalse();
            assertThat(tx.noRollbackFor()).isEmpty();
            assertThat(tx.noRollbackForClassName()).isEmpty();
        }
    }

    // ================================================================ helpers

    private record Target(Account account, StaffProfile profile) {
    }

    private void actAs(Role role, Long branchId) {
        when(branchScope.current()).thenReturn(new AccountPrincipal(ACTOR, "actor@petcare.test", 42L, role, branchId,
                false));
    }

    private Target target(Role role, AccountStatus status, Long branchId) {
        return targetWithId(TARGET, role, status, branchId);
    }

    /** Lần đọc không khóa và lần đọc dưới khóa cùng thấy {@code branchId}; test đổi field của hồ sơ để giả lập race. */
    private Target targetWithId(long id, Role role, AccountStatus status, Long branchId) {
        Account account = Account.createStaff("staff" + id + "@petcare.test", "0901234567", "hash", role);
        ReflectionTestUtils.setField(account, "id", id);
        ReflectionTestUtils.setField(account, "status", status);
        StaffProfile profile = BeanUtils.instantiateClass(StaffProfile.class);
        ReflectionTestUtils.setField(profile, "accountId", id);
        ReflectionTestUtils.setField(profile, "fullName", "Nhân viên " + id);
        ReflectionTestUtils.setField(profile, "branchId", branchId);
        when(staffProfiles.findStaffRow(id)).thenReturn(Optional.of(new StaffRow(id, "Nhân viên " + id, role,
                branchId, status, false, null)));
        when(accounts.findByIdForUpdate(id)).thenReturn(Optional.of(account));
        when(staffProfiles.findById(id)).thenReturn(Optional.of(profile));
        return new Target(account, profile);
    }

    private StaffDetailRow givenDetail(AccountStatus status, Long branchId) {
        StaffDetailRow row = new StaffDetailRow(TARGET, "staff7@petcare.test", "0901234567", "Nhân viên 7", Role.VET,
                status, false, branchId, null, null, status == AccountStatus.ACTIVE, null);
        when(staffProfiles.findStaffDetail(TARGET)).thenReturn(Optional.of(row));
        return row;
    }

    private AuditEntry capturedAudit() {
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).record(entry.capture());
        return entry.getValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> snapshot(Object value) {
        return (Map<String, Object>) value;
    }

    private void assertNoWrite() {
        verify(staffProfiles, never()).flush();
        verify(staffProfiles, never()).save(any());
        verify(accounts, never()).save(any());
        verifyNoInteractions(audit, sessions, notifications);
    }

    private static void assertRule(ThrowingCallable call, String ruleId, String message) {
        assertThatThrownBy(call).isInstanceOf(BusinessRuleViolationException.class).hasMessage(message)
                .hasMessageEndingWith("(" + ruleId + ")");
    }
}
