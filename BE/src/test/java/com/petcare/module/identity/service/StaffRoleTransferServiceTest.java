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
import com.petcare.module.care.api.NotificationApi;
import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.dto.ChangeRoleRequest;
import com.petcare.module.identity.dto.StaffResponse;
import com.petcare.module.identity.dto.TransferStaffRequest;
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
import com.petcare.module.sales.api.CashierShiftQueryApi;
import com.petcare.module.sales.api.CashierShiftQueryApi.OpenShift;
import com.petcare.module.visit.api.VisitQueryApi;
import com.petcare.module.visit.api.VisitQueryApi.AssignedVisit;
import com.petcare.module.visit.api.VisitQueryApi.VisitStatus;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ConcurrencyConflictException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;

/**
 * UC08 — đổi chức vụ (#18), điều chuyển (#19) (docs/adr/0031). Thứ tự: đọc không khóa (404) → khóa {@code branches}
 * → khóa {@code accounts} → đọc hồ sơ → phân cấp (403) → trùng giá trị (200 không ghi) → chi nhánh vừa đổi (409) →
 * BR-QT-03 → BR-QT-04 → BR-QT-08 → ghi → audit → flush → đọc response. Mỗi nhánh lỗi không gọi lệnh ghi nào; nhánh lỗi
 * đứng trước BR-QT-08 không gọi hai interface placeholder (nợ D016). Policy và mapper thật.
 */
class StaffRoleTransferServiceTest {

    private static final long ACTOR = 1L;
    private static final long TARGET = 7L;
    private static final long ACTIVE_BRANCH = 3L;
    private static final long DRAFT_BRANCH = 4L;
    private static final long OTHER_ACTIVE_BRANCH = 5L;
    private static final long UNKNOWN_BRANCH = 99L;
    private static final Instant NOW = Instant.parse("2026-10-12T03:00:00Z");
    private static final Map<Long, BranchStatus> BRANCHES = Map.of(ACTIVE_BRANCH, BranchStatus.ACTIVE,
            DRAFT_BRANCH, BranchStatus.DRAFT, OTHER_ACTIVE_BRANCH, BranchStatus.ACTIVE);

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
        when(visitQueries.findUnfinishedVisitsAssignedTo(anyLong())).thenReturn(List.of());
        actAs(Role.SUPER_MANAGER, null);
    }

    // ================================================================ #18 đổi chức vụ

    @Test
    void changeRoleLocksBranchThenAccountThenProfileThenWritesAuditsFlushesAndReadsBack() {
        Target target = target(Role.VET, AccountStatus.ACTIVE, ACTIVE_BRANCH);
        StaffDetailRow after = givenDetail(Role.RECEPTIONIST, ACTIVE_BRANCH);

        StaffResponse response = service.changeStaffRole(TARGET, new ChangeRoleRequest("RECEPTIONIST"));

        assertThat(response).isEqualTo(mapper.toResponse(after, false));
        InOrder order = inOrder(staffProfiles, branchLocks, accounts, audit);
        order.verify(staffProfiles).findStaffRow(TARGET);
        order.verify(branchLocks).lockForStaffChange(Set.of(ACTIVE_BRANCH));
        order.verify(accounts).findByIdForUpdate(TARGET);
        order.verify(staffProfiles).findById(TARGET);
        order.verify(audit).record(any());
        order.verify(staffProfiles).flush();
        order.verify(staffProfiles).findStaffDetail(TARGET);
        assertThat(target.account().getRole()).isEqualTo(Role.RECEPTIONIST);
        verify(staffProfiles, never()).countActiveBranchManagers(anyLong());
        verifyNoInteractions(visitQueries, cashierShifts, sessions, notifications);
    }

    @Test
    void vetLeavingClearsSpecialtyAndBioAndAuditsOldValues() {
        Target target = target(Role.VET, AccountStatus.ACTIVE, ACTIVE_BRANCH);
        ReflectionTestUtils.setField(target.profile(), "specialty", "Nội khoa");
        ReflectionTestUtils.setField(target.profile(), "bio", "Mô tả");
        givenDetail(Role.CARETAKER, ACTIVE_BRANCH);

        service.changeStaffRole(TARGET, new ChangeRoleRequest("CARETAKER"));

        assertThat(target.profile().getSpecialty()).isNull();
        assertThat(target.profile().getBio()).isNull();
        AuditEntry entry = capturedAudit();
        assertThat(entry.action()).isEqualTo(QtAuditActions.STAFF_ROLE_CHANGED);
        assertThat(entry.entityType()).isEqualTo("accounts");
        assertThat(entry.entityId()).isEqualTo(TARGET);
        assertThat(snapshot(entry.before())).containsExactly(Map.entry("role", "VET"),
                Map.entry("specialty", "Nội khoa"), Map.entry("bio", "Mô tả"));
        assertThat(snapshot(entry.after())).containsOnlyKeys("role", "specialty", "bio")
                .containsEntry("role", "CARETAKER").containsEntry("specialty", null).containsEntry("bio", null);
    }

    @Test
    void becomingVetKeepsExistingProfileFields() {
        Target target = target(Role.RECEPTIONIST, AccountStatus.ACTIVE, ACTIVE_BRANCH);
        ReflectionTestUtils.setField(target.profile(), "specialty", "Giữ");
        givenDetail(Role.VET, ACTIVE_BRANCH);

        service.changeStaffRole(TARGET, new ChangeRoleRequest("VET"));

        assertThat(target.account().getRole()).isEqualTo(Role.VET);
        assertThat(target.profile().getSpecialty()).isEqualTo("Giữ");
    }

    @Test
    void sameRoleIsNoOpWithoutWriteAuditFlushOrCount() {
        Target target = target(Role.VET, AccountStatus.ACTIVE, ACTIVE_BRANCH);
        ReflectionTestUtils.setField(target.profile(), "specialty", "Giữ");
        StaffDetailRow current = givenDetail(Role.VET, ACTIVE_BRANCH);

        StaffResponse response = service.changeStaffRole(TARGET, new ChangeRoleRequest("VET"));

        assertThat(response).isEqualTo(mapper.toResponse(current, false));
        assertThat(target.profile().getSpecialty()).isEqualTo("Giữ");
        verify(staffProfiles, never()).flush();
        verify(staffProfiles, never()).countActiveBranchManagers(anyLong());
        verifyNoInteractions(audit);
    }

    /**
     * BR-QT-04: chỉ chặn khi người bị tác động là BRANCH_MANAGER {@code ACTIVE} của chi nhánh {@code ACTIVE} và là người
     * cuối cùng (đếm tính cả người bị khóa, docs/adr/0024). DISABLED không được đếm nên không bị chặn (Q1).
     */
    @ParameterizedTest(name = "branch={0} status={1} locked={2} count={3} → rejected={4}")
    @CsvSource({
            "ACTIVE, ACTIVE,   false, 1, true",
            "ACTIVE, ACTIVE,   true,  1, true",
            "ACTIVE, ACTIVE,   false, 2, false",
            "DRAFT,  ACTIVE,   false, 1, false",
            "ACTIVE, DISABLED, false, 0, false",
            "ACTIVE, DISABLED, true,  0, false"})
    void lastManagerTruthTableForChangeRole(String branch, AccountStatus status, boolean locked, long count,
            boolean rejected) {
        long branchId = "ACTIVE".equals(branch) ? ACTIVE_BRANCH : DRAFT_BRANCH;
        Target target = target(Role.BRANCH_MANAGER, status, branchId);
        ReflectionTestUtils.setField(target.account(), "locked", locked);
        when(staffProfiles.countActiveBranchManagers(branchId)).thenReturn(count);
        givenDetail(Role.VET, branchId);

        ThrowingCallable call = () -> service.changeStaffRole(TARGET, new ChangeRoleRequest("VET"));

        if (rejected) {
            assertRule(call, "BR-QT-04",
                    "Không đổi chức vụ được: đây là quản lý cuối cùng của chi nhánh đang hoạt động (BR-QT-04)");
            assertThat(target.account().getRole()).isEqualTo(Role.BRANCH_MANAGER);
            assertNoWrite();
        } else {
            service.changeStaffRole(TARGET, new ChangeRoleRequest("VET"));
            assertThat(target.account().getRole()).isEqualTo(Role.VET);
        }
    }

    @Test
    void disabledOrLockedTargetCanChangeRole() {
        Target target = target(Role.VET, AccountStatus.DISABLED, ACTIVE_BRANCH);
        ReflectionTestUtils.setField(target.account(), "locked", true);
        givenDetail(Role.CARETAKER, ACTIVE_BRANCH);

        service.changeStaffRole(TARGET, new ChangeRoleRequest("CARETAKER"));

        assertThat(target.account().getRole()).isEqualTo(Role.CARETAKER);
        assertThat(target.account().getStatus()).isEqualTo(AccountStatus.DISABLED);
        assertThat(target.account().isLocked()).isTrue();
    }

    @Test
    void missingOrCustomerIs404BeforeAnyLock() {
        when(staffProfiles.findStaffRow(8L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.changeStaffRole(8L, new ChangeRoleRequest("VET")))
                .isInstanceOf(ResourceNotFoundException.class).hasMessage("Không tìm thấy nhân viên #8");
        assertThatThrownBy(() -> service.transferStaff(8L, new TransferStaffRequest(ACTIVE_BRANCH)))
                .isInstanceOf(ResourceNotFoundException.class).hasMessage("Không tìm thấy nhân viên #8");
        verifyNoInteractions(branchLocks);
        verify(accounts, never()).findByIdForUpdate(anyLong());
        assertNoWrite();
    }

    @Test
    void hierarchyDenialForChainLevelTargetSelfOrNonSuperManagerActorWritesNothing() {
        target(Role.SUPER_MANAGER, AccountStatus.ACTIVE, null);
        assertThatThrownBy(() -> service.changeStaffRole(TARGET, new ChangeRoleRequest("VET")))
                .isInstanceOf(StaffHierarchyDeniedException.class);
        verify(branchLocks).lockForStaffChange(Set.of());

        targetWithId(ACTOR, Role.BRANCH_MANAGER, AccountStatus.ACTIVE, ACTIVE_BRANCH);
        actAs(Role.BRANCH_MANAGER, ACTIVE_BRANCH);
        assertThatThrownBy(() -> service.changeStaffRole(ACTOR, new ChangeRoleRequest("VET")))
                .isInstanceOf(StaffHierarchyDeniedException.class);

        target(Role.VET, AccountStatus.ACTIVE, ACTIVE_BRANCH);
        actAs(Role.ADMIN, null);
        assertThatThrownBy(() -> service.changeStaffRole(TARGET, new ChangeRoleRequest("CARETAKER")))
                .isInstanceOf(StaffHierarchyDeniedException.class);

        verify(staffProfiles, never()).countActiveBranchManagers(anyLong());
        assertNoWrite();
    }

    @Test
    void branchMovedWhileWaitingIs409UnlessRequestBecameNoOp() {
        Target target = target(Role.VET, AccountStatus.ACTIVE, ACTIVE_BRANCH);
        ReflectionTestUtils.setField(target.profile(), "branchId", OTHER_ACTIVE_BRANCH);

        assertThatThrownBy(() -> service.changeStaffRole(TARGET, new ChangeRoleRequest("CARETAKER")))
                .isInstanceOf(ConcurrencyConflictException.class);
        assertThat(target.account().getRole()).isEqualTo(Role.VET);
        assertNoWrite();

        givenDetail(Role.VET, OTHER_ACTIVE_BRANCH);
        service.changeStaffRole(TARGET, new ChangeRoleRequest("VET"));
        verifyNoInteractions(audit);
    }

    @Test
    void branchStaffWithoutBranchIsBadData() {
        target(Role.VET, AccountStatus.ACTIVE, null);

        assertThatThrownBy(() -> service.changeStaffRole(TARGET, new ChangeRoleRequest("CARETAKER")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.transferStaff(TARGET, new TransferStaffRequest(ACTIVE_BRANCH)))
                .isInstanceOf(IllegalStateException.class);
        assertNoWrite();
    }

    // ================================================================ #19 điều chuyển

    @Test
    void transferLocksBothBranchesInOneCallThenAccountThenGuardsThenWritesAuditsFlushesAndReadsBack() {
        Target target = target(Role.VET, AccountStatus.ACTIVE, ACTIVE_BRANCH);
        StaffDetailRow after = givenDetail(Role.VET, DRAFT_BRANCH);

        StaffResponse response = service.transferStaff(TARGET, new TransferStaffRequest(DRAFT_BRANCH));

        assertThat(response).isEqualTo(mapper.toResponse(after, false));
        InOrder order = inOrder(staffProfiles, branchLocks, accounts, visitQueries, cashierShifts, audit);
        order.verify(staffProfiles).findStaffRow(TARGET);
        order.verify(branchLocks).lockForStaffChange(Set.of(ACTIVE_BRANCH, DRAFT_BRANCH));
        order.verify(accounts).findByIdForUpdate(TARGET);
        order.verify(staffProfiles).findById(TARGET);
        order.verify(visitQueries).findUnfinishedVisitsAssignedTo(TARGET);
        order.verify(cashierShifts).findOpenShift(TARGET);
        order.verify(audit).record(any());
        order.verify(staffProfiles).flush();
        order.verify(staffProfiles).findStaffDetail(TARGET);
        assertThat(target.profile().getBranchId()).isEqualTo(DRAFT_BRANCH);

        AuditEntry entry = capturedAudit();
        assertThat(entry.action()).isEqualTo(QtAuditActions.STAFF_TRANSFERRED);
        assertThat(entry.entityId()).isEqualTo(TARGET);
        assertThat(snapshot(entry.before())).containsExactly(Map.entry("branchId", ACTIVE_BRANCH));
        assertThat(snapshot(entry.after())).containsExactly(Map.entry("branchId", DRAFT_BRANCH));
        verifyNoInteractions(sessions, notifications);
    }

    @Test
    void sameBranchIsNoOpWithoutGuardsWriteOrAudit() {
        Target target = target(Role.RECEPTIONIST, AccountStatus.ACTIVE, ACTIVE_BRANCH);
        StaffDetailRow current = givenDetail(Role.RECEPTIONIST, ACTIVE_BRANCH);

        assertThat(service.transferStaff(TARGET, new TransferStaffRequest(ACTIVE_BRANCH)))
                .isEqualTo(mapper.toResponse(current, false));
        assertThat(target.profile().getBranchId()).isEqualTo(ACTIVE_BRANCH);
        verifyNoInteractions(visitQueries, cashierShifts, audit);
        verify(staffProfiles, never()).flush();
    }

    @Test
    void unknownBranchIsBrQt03BeforeLastManagerAndPendingWork() {
        Target target = target(Role.BRANCH_MANAGER, AccountStatus.ACTIVE, ACTIVE_BRANCH);

        assertRule(() -> service.transferStaff(TARGET, new TransferStaffRequest(UNKNOWN_BRANCH)), "BR-QT-03",
                "Chi nhánh không tồn tại (BR-QT-03)");
        assertThat(target.profile().getBranchId()).isEqualTo(ACTIVE_BRANCH);
        verify(staffProfiles, never()).countActiveBranchManagers(anyLong());
        verifyNoInteractions(visitQueries, cashierShifts);
        assertNoWrite();
    }

    @Test
    void lastManagerOfActiveSourceIsBrQt04BeforePendingWork() {
        Target target = target(Role.BRANCH_MANAGER, AccountStatus.ACTIVE, ACTIVE_BRANCH);
        when(staffProfiles.countActiveBranchManagers(ACTIVE_BRANCH)).thenReturn(1L);

        assertRule(() -> service.transferStaff(TARGET, new TransferStaffRequest(OTHER_ACTIVE_BRANCH)), "BR-QT-04",
                "Không điều chuyển được: đây là quản lý cuối cùng của chi nhánh đang hoạt động (BR-QT-04)");
        assertThat(target.profile().getBranchId()).isEqualTo(ACTIVE_BRANCH);
        verify(staffProfiles, never()).countActiveBranchManagers(OTHER_ACTIVE_BRANCH);
        verifyNoInteractions(visitQueries, cashierShifts);
        assertNoWrite();
    }

    @Test
    void managerLeavingDraftBranchOrDisabledManagerIsNotBlocked() {
        Target draft = target(Role.BRANCH_MANAGER, AccountStatus.ACTIVE, DRAFT_BRANCH);
        when(staffProfiles.countActiveBranchManagers(DRAFT_BRANCH)).thenReturn(1L);
        givenDetail(Role.BRANCH_MANAGER, ACTIVE_BRANCH);
        service.transferStaff(TARGET, new TransferStaffRequest(ACTIVE_BRANCH));
        assertThat(draft.profile().getBranchId()).isEqualTo(ACTIVE_BRANCH);

        Target disabled = target(Role.BRANCH_MANAGER, AccountStatus.DISABLED, ACTIVE_BRANCH);
        when(staffProfiles.countActiveBranchManagers(ACTIVE_BRANCH)).thenReturn(0L);
        givenDetail(Role.BRANCH_MANAGER, OTHER_ACTIVE_BRANCH);
        service.transferStaff(TARGET, new TransferStaffRequest(OTHER_ACTIVE_BRANCH));
        assertThat(disabled.profile().getBranchId()).isEqualTo(OTHER_ACTIVE_BRANCH);
    }

    @ParameterizedTest(name = "visits={0} shift={1}")
    @CsvSource(delimiter = '|', value = {
            "12,15 | false | Không điều chuyển được: còn lượt tiếp nhận #12, #15 chưa hoàn tất (BR-QT-08)",
            "      | true  | Không điều chuyển được: còn ca thu ngân đang mở (BR-QT-08)",
            "12    | true  | Không điều chuyển được: còn lượt tiếp nhận #12 chưa hoàn tất; còn ca thu ngân đang mở (BR-QT-08)"})
    void pendingWorkIsBrQt08ListingEverything(String visitIds, boolean openShift, String message) {
        Target target = target(Role.RECEPTIONIST, AccountStatus.ACTIVE, ACTIVE_BRANCH);
        List<AssignedVisit> visits = visitIds == null ? List.of()
                : java.util.Arrays.stream(visitIds.split(",")).map(Long::valueOf).map(id -> waitingVisit(id)).toList();
        when(visitQueries.findUnfinishedVisitsAssignedTo(TARGET)).thenReturn(visits);
        when(cashierShifts.findOpenShift(TARGET)).thenReturn(openShift(openShift));

        assertRule(() -> service.transferStaff(TARGET, new TransferStaffRequest(OTHER_ACTIVE_BRANCH)), "BR-QT-08",
                message);
        assertThat(target.profile().getBranchId()).isEqualTo(ACTIVE_BRANCH);
        assertNoWrite();
    }

    @Test
    void placeholderGuardFailurePropagatesWithoutWrite() {
        Target target = target(Role.VET, AccountStatus.ACTIVE, ACTIVE_BRANCH);
        when(visitQueries.findUnfinishedVisitsAssignedTo(TARGET))
                .thenThrow(new UnsupportedOperationException("placeholder"));

        assertThatThrownBy(() -> service.transferStaff(TARGET, new TransferStaffRequest(OTHER_ACTIVE_BRANCH)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(target.profile().getBranchId()).isEqualTo(ACTIVE_BRANCH);
        assertNoWrite();
    }

    @Test
    void transferBranchMovedIs409UnlessAlreadyAtDestination() {
        Target target = target(Role.VET, AccountStatus.ACTIVE, ACTIVE_BRANCH);
        ReflectionTestUtils.setField(target.profile(), "branchId", OTHER_ACTIVE_BRANCH);

        assertThatThrownBy(() -> service.transferStaff(TARGET, new TransferStaffRequest(DRAFT_BRANCH)))
                .isInstanceOf(ConcurrencyConflictException.class);
        assertNoWrite();

        givenDetail(Role.VET, OTHER_ACTIVE_BRANCH);
        service.transferStaff(TARGET, new TransferStaffRequest(OTHER_ACTIVE_BRANCH));
        verifyNoInteractions(audit, visitQueries, cashierShifts);
    }

    @Test
    void transferHierarchyDenialForChainLevelTargetOrSelfWritesNothing() {
        target(Role.SUPER_MANAGER, AccountStatus.ACTIVE, null);
        assertThatThrownBy(() -> service.transferStaff(TARGET, new TransferStaffRequest(ACTIVE_BRANCH)))
                .isInstanceOf(StaffHierarchyDeniedException.class);

        targetWithId(ACTOR, Role.BRANCH_MANAGER, AccountStatus.ACTIVE, ACTIVE_BRANCH);
        assertThatThrownBy(() -> service.transferStaff(ACTOR, new TransferStaffRequest(DRAFT_BRANCH)))
                .isInstanceOf(StaffHierarchyDeniedException.class);

        verifyNoInteractions(visitQueries, cashierShifts);
        assertNoWrite();
    }

    // ================================================================ transaction

    /**
     * Một transaction trọn use case, rollback với mọi lỗi (không {@code noRollbackFor}: không có bộ đếm nào phải sống
     * sót khi lỗi — khác đăng nhập / OTP, docs/adr/0031).
     */
    @Test
    void bothUseCasesAreSingleWritableTransactionsRollingBackOnEveryException() throws Exception {
        for (Method method : List.of(
                StaffAccountService.class.getMethod("changeStaffRole", Long.class, ChangeRoleRequest.class),
                StaffAccountService.class.getMethod("transferStaff", Long.class, TransferStaffRequest.class))) {
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

    private StaffDetailRow givenDetail(Role role, Long branchId) {
        StaffDetailRow row = new StaffDetailRow(TARGET, "staff7@petcare.test", "0901234567", "Nhân viên 7", role,
                AccountStatus.ACTIVE, false, branchId, null, null, false, null);
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

    /** Stub của {@code VisitQueryApi.findUnfinishedVisitsAssignedTo}: BR-QT-08 chỉ cần id của lượt. */
    private static AssignedVisit waitingVisit(long visitId) {
        return new AssignedVisit(visitId, 1L, VisitStatus.WAITING);
    }

    /** Stub của {@code CashierShiftQueryApi.findOpenShift}: BR-QT-08 chỉ cần có hay không có ca. */
    private static Optional<OpenShift> openShift(boolean open) {
        return open ? Optional.of(new OpenShift(77L, 1L)) : Optional.empty();
    }
}
