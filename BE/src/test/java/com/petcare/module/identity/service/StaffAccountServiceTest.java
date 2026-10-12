package com.petcare.module.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.branch.api.BranchApi;
import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.branch.api.BranchQueryApi.BranchStatus;
import com.petcare.module.branch.api.BranchQueryApi.BranchSummary;
import com.petcare.module.care.api.NotificationApi;
import com.petcare.module.care.api.NotificationApi.Channel;
import com.petcare.module.care.api.NotificationApi.NotificationRequest;
import com.petcare.module.identity.api.NotificationTemplateCode;
import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.dto.CreateStaffRequest;
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
import com.petcare.module.identity.service.TemporaryPasswordGenerator.TemporaryPassword;
import com.petcare.module.sales.api.CashierShiftQueryApi;
import com.petcare.module.visit.api.VisitQueryApi;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;

/**
 * UC08 — tạo nhân viên, gửi lại mật khẩu ban đầu (docs/adr/0029 mục 3, docs/adr/0030). Thứ tự kiểm: phân cấp →
 * BR-QT-03 → BR-TK-01 → ghi; mỗi nhánh lỗi không gọi lệnh ghi nào (lỗi ném trước ghi = transaction rollback sạch).
 * Gửi lại: khóa {@code accounts} là lệnh đọc đầu tiên, rồi mới đọc hồ sơ, kiểm phân cấp, 409, ghi, hủy phiên. Policy,
 * FSM và mapper thật.
 */
class StaffAccountServiceTest {

    private static final long ACTOR = 1L;
    private static final long NEW_ID = 50L;
    private static final long OWN_BRANCH = 3L;
    private static final long OTHER_BRANCH = 4L;
    private static final Instant NOW = Instant.parse("2026-10-11T03:00:00Z");
    private static final TemporaryPassword TEMP = new TemporaryPassword("Abc23456defg", "$2a$10$hash");

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
        when(branches.findBranch(OWN_BRANCH)).thenReturn(Optional.of(branch(OWN_BRANCH, BranchStatus.ACTIVE)));
        when(branches.findBranch(OTHER_BRANCH)).thenReturn(Optional.of(branch(OTHER_BRANCH, BranchStatus.DRAFT)));
        when(accounts.save(any(Account.class))).thenAnswer(invocation -> {
            Account account = invocation.getArgument(0);
            ReflectionTestUtils.setField(account, "id", NEW_ID);
            return account;
        });
        when(staffProfiles.save(any(StaffProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    // ================================================================ #16 tạo

    @Test
    void adminCreatesSuperManagerInOrderWithNormalizedInput() {
        actAs(Role.ADMIN, null);
        StaffDetailRow row = row(NEW_ID, "sm@petcare.test", Role.SUPER_MANAGER, null);
        when(staffProfiles.findStaffDetail(NEW_ID)).thenReturn(Optional.of(row));

        StaffResponse response = service.createStaff(
                request("  SM@PetCare.TEST ", "0901234567", "  Quản lý chuỗi  ", "SUPER_MANAGER", null), TEMP);

        assertThat(response).isEqualTo(mapper.toResponse(row, false));
        InOrder order = inOrder(accounts, staffProfiles, notifications, audit);
        order.verify(accounts).existsByEmail("sm@petcare.test");
        ArgumentCaptor<Account> account = ArgumentCaptor.forClass(Account.class);
        order.verify(accounts).save(account.capture());
        ArgumentCaptor<StaffProfile> profile = ArgumentCaptor.forClass(StaffProfile.class);
        order.verify(staffProfiles).save(profile.capture());
        order.verify(notifications).enqueue(any());
        order.verify(audit).record(any());
        order.verify(staffProfiles).flush();
        order.verify(staffProfiles).findStaffDetail(NEW_ID);

        assertThat(account.getValue().getEmail()).isEqualTo("sm@petcare.test");
        assertThat(account.getValue().getPasswordHash()).isEqualTo(TEMP.passwordHash());
        assertThat(account.getValue().getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.getValue().isMustChangePassword()).isTrue();
        assertThat(profile.getValue().getFullName()).isEqualTo("Quản lý chuỗi");
        assertThat(profile.getValue().getBranchId()).isNull();
        verifyNoInteractions(branches, sessions, branchLocks, visitQueries, cashierShifts);
    }

    @Test
    void outboxPayloadHasExactlyTemplateVarsAndAuditHasNoSecret() {
        actAs(Role.SUPER_MANAGER, null);
        when(staffProfiles.findStaffDetail(NEW_ID)).thenReturn(Optional.of(row(NEW_ID, "vet@petcare.test",
                Role.VET, OWN_BRANCH)));

        service.createStaff(request("vet@petcare.test", "0901234567", "Bác sĩ An", "VET", OWN_BRANCH), TEMP);

        ArgumentCaptor<NotificationRequest> mail = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notifications).enqueue(mail.capture());
        assertThat(mail.getValue().templateCode()).isEqualTo(NotificationTemplateCode.STAFF_TEMP_PASSWORD);
        assertThat(mail.getValue().channel()).isEqualTo(Channel.EMAIL);
        assertThat(mail.getValue().recipientEmail()).isEqualTo("vet@petcare.test");
        assertThat(mail.getValue().payload()).containsExactlyInAnyOrderEntriesOf(Map.of("ho_ten", "Bác sĩ An",
                "email_dang_nhap", "vet@petcare.test", "mat_khau_tam", TEMP.plainPassword()));

        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).record(entry.capture());
        assertThat(entry.getValue().action()).isEqualTo(QtAuditActions.STAFF_CREATED);
        assertThat(entry.getValue().entityType()).isEqualTo("accounts");
        assertThat(entry.getValue().entityId()).isEqualTo(NEW_ID);
        assertThat(entry.getValue().before()).isNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> after = (Map<String, Object>) entry.getValue().after();
        assertThat(after).containsOnlyKeys("email", "phone", "fullName", "role", "branchId", "status")
                .containsEntry("role", "VET").containsEntry("branchId", OWN_BRANCH).containsEntry("status", "ACTIVE");
        assertThat(after.values()).doesNotContain(TEMP.plainPassword(), TEMP.passwordHash());
    }

    @Test
    void branchManagerWithoutBranchCreatesInOwnBranch() {
        actAs(Role.BRANCH_MANAGER, OWN_BRANCH);
        when(staffProfiles.findStaffDetail(NEW_ID)).thenReturn(Optional.of(row(NEW_ID, "a@b.c", Role.CARETAKER,
                OWN_BRANCH)));

        service.createStaff(request("a@b.c", "0901234567", "A", "CARETAKER", null), TEMP);

        ArgumentCaptor<StaffProfile> profile = ArgumentCaptor.forClass(StaffProfile.class);
        verify(staffProfiles).save(profile.capture());
        assertThat(profile.getValue().getBranchId()).isEqualTo(OWN_BRANCH);
        verify(branches).findBranch(OWN_BRANCH);
    }

    @Test
    void superManagerCreatesInDraftBranch() {
        actAs(Role.SUPER_MANAGER, null);
        when(staffProfiles.findStaffDetail(NEW_ID)).thenReturn(Optional.of(row(NEW_ID, "a@b.c", Role.BRANCH_MANAGER,
                OTHER_BRANCH)));

        service.createStaff(request("a@b.c", "0901234567", "A", "BRANCH_MANAGER", OTHER_BRANCH), TEMP);

        verify(accounts).save(any());
    }

    @Test
    void hierarchyDenialComesBeforeEveryOtherCheckAndWrite() {
        record Case(Role actor, Long actorBranch, String role, Long branchId) {}
        List<Case> cases = List.of(
                new Case(Role.ADMIN, null, "VET", OWN_BRANCH),
                new Case(Role.SUPER_MANAGER, null, "SUPER_MANAGER", null),
                new Case(Role.BRANCH_MANAGER, OWN_BRANCH, "BRANCH_MANAGER", null),
                new Case(Role.BRANCH_MANAGER, OWN_BRANCH, "VET", OTHER_BRANCH));
        for (Case c : cases) {
            actAs(c.actor(), c.actorBranch());

            assertThatThrownBy(() -> service.createStaff(request("x@y.z", "0901234567", "X", c.role(), c.branchId()),
                    TEMP)).as(c.toString()).isInstanceOf(StaffHierarchyDeniedException.class);
        }
        verify(accounts, never()).existsByEmail(anyString());
        verifyNoInteractions(branches, notifications, audit);
        verify(accounts, never()).save(any());
        verify(staffProfiles, never()).save(any());
    }

    @Test
    void superManagerRoleWithBranchIsBrQt03BeforeEmailCheck() {
        actAs(Role.ADMIN, null);

        assertRule(() -> service.createStaff(request("x@y.z", "0901234567", "X", "SUPER_MANAGER", OWN_BRANCH), TEMP),
                "BR-QT-03");
        assertNoWrite();
        verify(accounts, never()).existsByEmail(anyString());
    }

    @Test
    void branchStaffWithoutBranchIsBrQt03() {
        actAs(Role.SUPER_MANAGER, null);

        assertRule(() -> service.createStaff(request("x@y.z", "0901234567", "X", "RECEPTIONIST", null), TEMP),
                "BR-QT-03");
        assertNoWrite();
    }

    @Test
    void unknownBranchIsBrQt03() {
        actAs(Role.SUPER_MANAGER, null);
        when(branches.findBranch(99L)).thenReturn(Optional.empty());

        assertRule(() -> service.createStaff(request("x@y.z", "0901234567", "X", "VET", 99L), TEMP), "BR-QT-03");
        assertNoWrite();
        verify(accounts, never()).existsByEmail(anyString());
    }

    @Test
    void usedEmailIsBrTk01CheckedAfterNormalization() {
        actAs(Role.SUPER_MANAGER, null);
        when(accounts.existsByEmail("used@petcare.test")).thenReturn(true);

        assertRule(() -> service.createStaff(request(" Used@PetCare.test", "0901234567", "X", "VET", OWN_BRANCH),
                TEMP), "BR-TK-01");
        assertNoWrite();
    }

    // ================================================================ #22 gửi lại mật khẩu ban đầu

    @Test
    void resendLocksFirstThenReadsProfileThenWritesThenRevokesThenMailsThenAudits() {
        actAs(Role.SUPER_MANAGER, null);
        Account target = staff(7L, Role.VET, AccountStatus.ACTIVE, true);
        givenTarget(target, OWN_BRANCH, "Bác sĩ An");

        service.resendInitialPassword(7L, TEMP);

        InOrder order = inOrder(accounts, staffProfiles, sessions, notifications, audit);
        order.verify(accounts).findByIdForUpdate(7L);
        order.verify(staffProfiles).findById(7L);
        order.verify(sessions).revokeAll(7L);
        order.verify(notifications).enqueue(any());
        order.verify(audit).record(any());
        assertThat(target.getPasswordHash()).isEqualTo(TEMP.passwordHash());

        ArgumentCaptor<NotificationRequest> mail = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notifications).enqueue(mail.capture());
        assertThat(mail.getValue().recipientEmail()).isEqualTo("staff7@petcare.test");
        assertThat(mail.getValue().payload()).containsExactlyInAnyOrderEntriesOf(Map.of("ho_ten", "Bác sĩ An",
                "email_dang_nhap", "staff7@petcare.test", "mat_khau_tam", TEMP.plainPassword()));
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).record(entry.capture());
        assertThat(entry.getValue().action()).isEqualTo(QtAuditActions.STAFF_INITIAL_PASSWORD_RESENT);
        assertThat(entry.getValue().entityId()).isEqualTo(7L);
        assertThat(entry.getValue().before()).isNull();
        assertThat(entry.getValue().after()).isNull();
    }

    @Test
    void resendAllowedWhileAdminLocked() {
        actAs(Role.SUPER_MANAGER, null);
        Account target = staff(7L, Role.VET, AccountStatus.ACTIVE, true);
        ReflectionTestUtils.setField(target, "locked", true);
        givenTarget(target, OWN_BRANCH, "A");

        service.resendInitialPassword(7L, TEMP);

        verify(sessions).revokeAll(7L);
    }

    @Test
    void resendMissingOrCustomerIs404WithoutReadingProfile() {
        actAs(Role.SUPER_MANAGER, null);
        when(accounts.findByIdForUpdate(8L)).thenReturn(Optional.empty());
        Account customer = Account.registerCustomer("c@b.c", "h", NOW);
        customer.verify();
        ReflectionTestUtils.setField(customer, "id", 9L);
        when(accounts.findByIdForUpdate(9L)).thenReturn(Optional.of(customer));

        assertThatThrownBy(() -> service.resendInitialPassword(8L, TEMP)).isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Không tìm thấy nhân viên #8");
        assertThatThrownBy(() -> service.resendInitialPassword(9L, TEMP)).isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Không tìm thấy nhân viên #9");
        verify(staffProfiles, never()).findById(anyLong());
        assertNoResendWrite();
    }

    @Test
    void resendOutsideHierarchyOrSelfIsDeniedWithoutWrite() {
        actAs(Role.BRANCH_MANAGER, OWN_BRANCH);
        givenTarget(staff(7L, Role.VET, AccountStatus.ACTIVE, true), OTHER_BRANCH, "A");
        givenTarget(staff(ACTOR, Role.BRANCH_MANAGER, AccountStatus.ACTIVE, true), OWN_BRANCH, "Me");

        assertThatThrownBy(() -> service.resendInitialPassword(7L, TEMP))
                .isInstanceOf(StaffHierarchyDeniedException.class);
        assertThatThrownBy(() -> service.resendInitialPassword(ACTOR, TEMP))
                .isInstanceOf(StaffHierarchyDeniedException.class);
        assertNoResendWrite();
    }

    @Test
    void resendDisabledOrAlreadyChangedIs409WithoutWrite() {
        actAs(Role.SUPER_MANAGER, null);
        Account disabled = staff(7L, Role.VET, AccountStatus.DISABLED, true);
        Account changed = staff(8L, Role.VET, AccountStatus.ACTIVE, false);
        givenTarget(disabled, OWN_BRANCH, "A");
        givenTarget(changed, OWN_BRANCH, "B");

        assertThatThrownBy(() -> service.resendInitialPassword(7L, TEMP))
                .isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(() -> service.resendInitialPassword(8L, TEMP))
                .isInstanceOf(InvalidStateTransitionException.class).hasMessageContaining("đã đổi mật khẩu lần đầu");
        assertThat(disabled.getPasswordHash()).isEqualTo("old-hash");
        assertThat(changed.getPasswordHash()).isEqualTo("old-hash");
        assertNoResendWrite();
    }

    @Test
    void resendStaffWithoutProfileIsBadData() {
        actAs(Role.SUPER_MANAGER, null);
        when(accounts.findByIdForUpdate(7L)).thenReturn(Optional.of(staff(7L, Role.VET, AccountStatus.ACTIVE, true)));
        when(staffProfiles.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resendInitialPassword(7L, TEMP)).isInstanceOf(IllegalStateException.class);
        assertNoResendWrite();
    }

    // ================================================================ helpers

    private void actAs(Role role, Long branchId) {
        when(branchScope.current()).thenReturn(new AccountPrincipal(ACTOR, "actor@petcare.test", 42L, role, branchId,
                false));
    }

    private void givenTarget(Account account, Long branchId, String fullName) {
        when(accounts.findByIdForUpdate(account.getId())).thenReturn(Optional.of(account));
        StaffProfile profile = BeanUtils.instantiateClass(StaffProfile.class);
        ReflectionTestUtils.setField(profile, "accountId", account.getId());
        ReflectionTestUtils.setField(profile, "fullName", fullName);
        ReflectionTestUtils.setField(profile, "branchId", branchId);
        when(staffProfiles.findById(account.getId())).thenReturn(Optional.of(profile));
    }

    private static Account staff(long id, Role role, AccountStatus status, boolean mustChangePassword) {
        Account account = Account.createStaff("staff" + id + "@petcare.test", "0901234567", "old-hash", role);
        ReflectionTestUtils.setField(account, "id", id);
        ReflectionTestUtils.setField(account, "status", status);
        ReflectionTestUtils.setField(account, "mustChangePassword", mustChangePassword);
        return account;
    }

    private static CreateStaffRequest request(String email, String phone, String fullName, String role,
            Long branchId) {
        return new CreateStaffRequest(email, phone, fullName, role, branchId);
    }

    private static StaffDetailRow row(long id, String email, Role role, Long branchId) {
        return new StaffDetailRow(id, email, "0901234567", "Tên", role, AccountStatus.ACTIVE, false, branchId, null,
                null, true, null);
    }

    private static BranchSummary branch(long id, BranchStatus status) {
        return new BranchSummary(id, "CN " + id, "Địa chỉ", "0280000000", status, false);
    }

    private void assertNoWrite() {
        verify(accounts, never()).save(any());
        verify(staffProfiles, never()).save(any());
        verifyNoInteractions(notifications, audit, sessions);
    }

    private void assertNoResendWrite() {
        verifyNoInteractions(sessions, notifications, audit);
    }

    private static void assertRule(ThrowingCallable call, String ruleId) {
        assertThatThrownBy(call).isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageEndingWith("(" + ruleId + ")");
    }
}
