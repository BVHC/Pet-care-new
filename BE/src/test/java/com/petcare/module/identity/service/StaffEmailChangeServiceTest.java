package com.petcare.module.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.care.api.NotificationApi;
import com.petcare.module.care.api.NotificationApi.Channel;
import com.petcare.module.care.api.NotificationApi.NotificationRequest;
import com.petcare.module.identity.api.NotificationTemplateCode;
import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.dto.OtpSentResponse;
import com.petcare.module.identity.dto.StaffResponse;
import com.petcare.module.identity.entity.Account;
import com.petcare.module.identity.entity.AccountStatus;
import com.petcare.module.identity.entity.OtpPurpose;
import com.petcare.module.identity.entity.StaffProfile;
import com.petcare.module.identity.exception.OtpRejectedException;
import com.petcare.module.identity.exception.StaffHierarchyDeniedException;
import com.petcare.module.identity.mapper.StaffAdminMapper;
import com.petcare.module.identity.repository.AccountRepository;
import com.petcare.module.identity.repository.StaffDetailRow;
import com.petcare.module.identity.repository.StaffProfileRepository;
import com.petcare.module.identity.repository.StaffRow;
import com.petcare.module.identity.service.OtpService.AccountBoundOtp;
import com.petcare.module.identity.service.OtpService.IssuedOtp;
import com.petcare.module.identity.service.OtpService.PreparedOtp;
import com.petcare.module.identity.service.StaffEmailChangeService.EmailChangeAttempt;
import com.petcare.module.identity.service.StaffEmailChangeService.EmailChangeCode;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;

/**
 * UC08 — sửa hộ email nhân viên (identity-v1 #23, #24; docs/adr/0034). Thứ tự:
 * <ul>
 *   <li>#23: khóa {@code accounts} (404) → hồ sơ → phân cấp (403) → trùng email hiện tại / tài khoản khác (BR-TK-01)
 *       → {@code issuePrepared} (BR-TK-07 bên trong) → outbox.</li>
 *   <li>#24 đọc: projection (404) → phân cấp (403) → mã còn mở; #24 ghi: khóa → hồ sơ → phân cấp lại → settle (lệnh
 *       ghi đầu tiên) → BR-TK-01 → đổi email → 2 thông báo → audit → flush → đọc response.</li>
 * </ul>
 * Mỗi nhánh lỗi không gọi lệnh ghi nào đứng sau nó. Policy và mapper thật; {@code OtpService} mock (đã có test riêng).
 */
class StaffEmailChangeServiceTest {

    private static final long ACTOR = 1L;
    private static final long TARGET = 7L;
    private static final long BRANCH = 3L;
    private static final long OTHER_BRANCH = 5L;
    private static final String OLD_EMAIL = "staff7@petcare.test";
    private static final String NEW_EMAIL = "moi7@petcare.test";
    private static final Instant NOW = Instant.parse("2026-10-12T03:04:00Z");
    private static final PreparedOtp PREPARED = new PreparedOtp("123456", "$2a$otp");
    private static final IssuedOtp ISSUED = new IssuedOtp("123456", NOW.plusSeconds(300), NOW.plusSeconds(60), 5);

    private final AccountRepository accounts = mock(AccountRepository.class);
    private final StaffProfileRepository staffProfiles = mock(StaffProfileRepository.class);
    private final BranchScope branchScope = mock(BranchScope.class);
    private final OtpService otps = mock(OtpService.class);
    private final NotificationApi notifications = mock(NotificationApi.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);
    private final StaffPresence presence = mock(StaffPresence.class);
    private final StaffAdminMapper mapper = Mappers.getMapper(StaffAdminMapper.class);
    private final StaffEmailChangeService service = new StaffEmailChangeService(accounts, staffProfiles,
            new StaffHierarchyPolicy(), branchScope, otps, notifications, audit, mapper, presence,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        when(presence.onlineSince()).thenReturn(NOW.minusSeconds(600));
        when(otps.issuePrepared(anyLong(), any(), anyString(), any(PreparedOtp.class))).thenReturn(ISSUED);
        actAs(Role.SUPER_MANAGER, null);
    }

    // ================================================================ #23 gửi mã

    @Test
    void issueLocksThenGuardsThenIssuesCodeToNormalizedNewEmailAndQueuesOtpMail() {
        target(Role.VET, AccountStatus.ACTIVE, BRANCH);

        OtpSentResponse response = service.issueEmailChangeOtp(TARGET, "  Moi7@PetCare.TEST ", PREPARED);

        assertThat(response).isEqualTo(new OtpSentResponse(NOW.plusSeconds(60), null));
        InOrder order = inOrder(accounts, staffProfiles, otps, notifications);
        order.verify(accounts).findByIdForUpdate(TARGET);
        order.verify(staffProfiles).findById(TARGET);
        order.verify(accounts).existsByEmail(NEW_EMAIL);
        order.verify(otps).issuePrepared(TARGET, OtpPurpose.CHANGE_EMAIL, NEW_EMAIL, PREPARED);
        order.verify(notifications).enqueue(new NotificationRequest(NotificationTemplateCode.OTP_EMAIL_CHANGE,
                Channel.EMAIL, null, NEW_EMAIL, Map.of("ma_otp", "123456", "thoi_han_phut", 5), null));
        verifyNoInteractions(audit);
        verify(accounts, never()).flush();
    }

    /** Q1 (docs/adr/0034): không kiểm trạng thái — DISABLED, bị khóa vẫn gửi được; ADMIN sửa SUPER_MANAGER. */
    @ParameterizedTest(name = "actor={0} target={1} status={2} locked={3}")
    @CsvSource({
            "SUPER_MANAGER,  VET,           DISABLED, false",
            "SUPER_MANAGER,  BRANCH_MANAGER, ACTIVE,  true",
            "ADMIN,          SUPER_MANAGER, ACTIVE,   false",
            "BRANCH_MANAGER, RECEPTIONIST,  DISABLED, true"})
    void issueWorksForAnyStatusInsideHierarchy(Role actor, Role role, AccountStatus status, boolean locked) {
        actAs(actor, actor == Role.BRANCH_MANAGER ? BRANCH : null);
        Target target = target(role, status, role == Role.SUPER_MANAGER ? null : BRANCH);
        ReflectionTestUtils.setField(target.account(), "locked", locked);

        service.issueEmailChangeOtp(TARGET, NEW_EMAIL, PREPARED);

        verify(otps).issuePrepared(TARGET, OtpPurpose.CHANGE_EMAIL, NEW_EMAIL, PREPARED);
        assertThat(target.account().getStatus()).isEqualTo(status);
        assertThat(target.account().getEmail()).isEqualTo(OLD_EMAIL);
    }

    @Test
    void issueMissingOrCustomerTargetIs404BeforeAnythingElse() {
        when(accounts.findByIdForUpdate(TARGET)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.issueEmailChangeOtp(TARGET, NEW_EMAIL, PREPARED))
                .isInstanceOf(ResourceNotFoundException.class);

        Account customer = Account.registerCustomer("kh@petcare.test", "hash", NOW.plusSeconds(3600));
        ReflectionTestUtils.setField(customer, "id", TARGET);
        when(accounts.findByIdForUpdate(TARGET)).thenReturn(Optional.of(customer));
        assertThatThrownBy(() -> service.issueEmailChangeOtp(TARGET, NEW_EMAIL, PREPARED))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(staffProfiles, never()).findById(anyLong());
        verify(accounts, never()).existsByEmail(any());
        verifyNoInteractions(otps, notifications, audit);
    }

    /** Ngoài phân cấp và chính mình → 403 trước BR-TK-01: người không có quyền không dò được email đã tồn tại. */
    @ParameterizedTest(name = "actor={0}@{1} target={2}@{3} self={4}")
    @CsvSource({
            "BRANCH_MANAGER, 3, VET,            5, false",
            "BRANCH_MANAGER, 3, BRANCH_MANAGER, 3, false",
            "ADMIN,          ,  VET,            3, false",
            "SUPER_MANAGER,  ,  SUPER_MANAGER,  ,  true"})
    void issueOutsideHierarchyIs403BeforeEmailChecks(Role actor, Long actorBranch, Role role, Long branch,
            boolean self) {
        actAs(actor, actorBranch);
        if (self) {
            when(branchScope.current()).thenReturn(new AccountPrincipal(TARGET, OLD_EMAIL, 42L, actor, actorBranch,
                    false));
        }
        target(role, AccountStatus.ACTIVE, branch);
        when(accounts.existsByEmail(NEW_EMAIL)).thenReturn(true);

        assertThatThrownBy(() -> service.issueEmailChangeOtp(TARGET, NEW_EMAIL, PREPARED))
                .isInstanceOf(StaffHierarchyDeniedException.class);
        verify(accounts, never()).existsByEmail(any());
        verifyNoInteractions(otps, notifications, audit);
    }

    /** Q5: trùng email hiện tại (sau chuẩn hóa) → BR-TK-01 message riêng, không gửi mã, không tiêu quota. */
    @Test
    void issueSameAsCurrentEmailIsBrTk01WithoutCode() {
        target(Role.VET, AccountStatus.ACTIVE, BRANCH);

        assertRule(() -> service.issueEmailChangeOtp(TARGET, " STAFF7@petcare.test", PREPARED), "BR-TK-01",
                "Email mới trùng email hiện tại (BR-TK-01)");
        verify(accounts, never()).existsByEmail(any());
        verifyNoInteractions(otps, notifications, audit);
    }

    @Test
    void issueEmailOfAnotherAccountIsBrTk01WithoutCode() {
        target(Role.VET, AccountStatus.ACTIVE, BRANCH);
        when(accounts.existsByEmail(NEW_EMAIL)).thenReturn(true);

        assertRule(() -> service.issueEmailChangeOtp(TARGET, NEW_EMAIL, PREPARED), "BR-TK-01",
                "Email đã được sử dụng (BR-TK-01)");
        verifyNoInteractions(otps, notifications, audit);
    }

    @Test
    void issueQuotaRejectionQueuesNoMail() {
        target(Role.VET, AccountStatus.ACTIVE, BRANCH);
        BusinessRuleViolationException quota = new BusinessRuleViolationException("BR-TK-07", "chờ 30 giây");
        when(otps.issuePrepared(anyLong(), any(), anyString(), any(PreparedOtp.class))).thenThrow(quota);

        assertThatThrownBy(() -> service.issueEmailChangeOtp(TARGET, NEW_EMAIL, PREPARED)).isSameAs(quota);
        verifyNoInteractions(notifications, audit);
    }

    // ================================================================ #24 bước đọc

    @Test
    void findReturnsOpenCodeWithItsTargetEmail() {
        target(Role.VET, AccountStatus.ACTIVE, BRANCH);
        when(otps.findActiveForAccount(TARGET, OtpPurpose.CHANGE_EMAIL))
                .thenReturn(Optional.of(new AccountBoundOtp(41L, NEW_EMAIL, "$2a$code")));

        assertThat(service.findEmailChangeCode(TARGET)).contains(new EmailChangeCode(41L, NEW_EMAIL, "$2a$code"));
        verify(accounts, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void findWithoutCodeIsEmpty() {
        target(Role.VET, AccountStatus.ACTIVE, BRANCH);
        when(otps.findActiveForAccount(TARGET, OtpPurpose.CHANGE_EMAIL)).thenReturn(Optional.empty());

        assertThat(service.findEmailChangeCode(TARGET)).isEmpty();
    }

    @Test
    void find404AndHierarchy403HappenBeforeCodeLookup() {
        when(staffProfiles.findStaffRow(TARGET)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.findEmailChangeCode(TARGET)).isInstanceOf(ResourceNotFoundException.class);

        actAs(Role.BRANCH_MANAGER, BRANCH);
        target(Role.VET, AccountStatus.ACTIVE, OTHER_BRANCH);
        assertThatThrownBy(() -> service.findEmailChangeCode(TARGET))
                .isInstanceOf(StaffHierarchyDeniedException.class);

        verifyNoInteractions(otps);
    }

    // ================================================================ #24 bước ghi

    @Test
    void applyLocksGuardsSettlesFirstThenChangesEmailNotifiesBothAuditsFlushesAndReadsBack() {
        Target target = target(Role.VET, AccountStatus.ACTIVE, BRANCH);
        StaffDetailRow after = givenDetail(NEW_EMAIL);

        StaffResponse response = service.applyEmailChange(TARGET, new EmailChangeAttempt(41L, NEW_EMAIL, true));

        assertThat(response).isEqualTo(mapper.toResponse(after, false));
        assertThat(target.account().getEmail()).isEqualTo(NEW_EMAIL);
        InOrder order = inOrder(accounts, staffProfiles, otps, notifications, audit);
        order.verify(accounts).findByIdForUpdate(TARGET);
        order.verify(staffProfiles).findById(TARGET);
        order.verify(otps).settleCheckedOtp(TARGET, OtpPurpose.CHANGE_EMAIL, NEW_EMAIL, 41L, true);
        order.verify(accounts).existsByEmail(NEW_EMAIL);
        Map<String, Object> payload = Map.of("email_cu", OLD_EMAIL, "email_moi", NEW_EMAIL,
                "thoi_diem", "10:04 12/10/2026");
        order.verify(notifications).enqueue(new NotificationRequest(NotificationTemplateCode.EMAIL_CHANGED_NOTICE,
                Channel.EMAIL, null, OLD_EMAIL, payload, null));
        order.verify(notifications).enqueue(new NotificationRequest(NotificationTemplateCode.EMAIL_CHANGED_NOTICE,
                Channel.EMAIL, null, NEW_EMAIL, payload, null));
        order.verify(audit).record(any());
        order.verify(accounts).flush();
        order.verify(staffProfiles).findStaffDetail(TARGET);

        AuditEntry entry = capturedAudit();
        assertThat(entry.action()).isEqualTo(QtAuditActions.STAFF_EMAIL_CHANGED);
        assertThat(entry.entityType()).isEqualTo("accounts");
        assertThat(entry.entityId()).isEqualTo(TARGET);
        assertThat(snapshot(entry.before())).containsExactly(Map.entry("email", OLD_EMAIL));
        assertThat(snapshot(entry.after())).containsExactly(Map.entry("email", NEW_EMAIL),
                Map.entry("verificationMethod", "EMAIL_CODE"));
    }

    /** Đổi email chỉ đổi {@code email}: trạng thái, khóa, cờ đổi mật khẩu, chi nhánh giữ nguyên (Q1, Q2). */
    @Test
    void applyKeepsStatusLockFlagsAndBranch() {
        Target target = target(Role.CARETAKER, AccountStatus.DISABLED, BRANCH);
        ReflectionTestUtils.setField(target.account(), "locked", true);
        givenDetail(NEW_EMAIL);

        service.applyEmailChange(TARGET, new EmailChangeAttempt(41L, NEW_EMAIL, true));

        assertThat(target.account().getStatus()).isEqualTo(AccountStatus.DISABLED);
        assertThat(target.account().isLocked()).isTrue();
        assertThat(target.account().isMustChangePassword()).isTrue();
        assertThat(target.profile().getBranchId()).isEqualTo(BRANCH);
    }

    /** Phân cấp kiểm lại dưới khóa: vừa bị điều chuyển sang chi nhánh khác → 403, chưa đụng mã. */
    @Test
    void applyHierarchyDeniedUnderLockNeverTouchesCode() {
        actAs(Role.BRANCH_MANAGER, BRANCH);
        Target target = target(Role.VET, AccountStatus.ACTIVE, BRANCH);
        ReflectionTestUtils.setField(target.profile(), "branchId", OTHER_BRANCH);

        assertThatThrownBy(() -> service.applyEmailChange(TARGET, new EmailChangeAttempt(41L, NEW_EMAIL, true)))
                .isInstanceOf(StaffHierarchyDeniedException.class);
        verifyNoInteractions(otps);
        assertNoWriteAfterSettle(target);
    }

    @Test
    void applyRejectedCodeStopsBeforeAnyOtherWrite() {
        Target target = target(Role.VET, AccountStatus.ACTIVE, BRANCH);
        OtpRejectedException wrong = new OtpRejectedException("BR-TK-05", OtpService.INVALID_OTP_MESSAGE);
        doThrow(wrong).when(otps).settleCheckedOtp(anyLong(), any(), anyString(), anyLong(), anyBoolean());

        assertThatThrownBy(() -> service.applyEmailChange(TARGET, new EmailChangeAttempt(41L, NEW_EMAIL, false)))
                .isSameAs(wrong);
        verify(accounts, never()).existsByEmail(any());
        assertNoWriteAfterSettle(target);
    }

    /** Email vừa bị tài khoản khác dùng: BR-TK-01 sau settle → rollback cả {@code consumed_at} (transaction thật). */
    @Test
    void applyEmailTakenMeanwhileIsBrTk01AfterSettleAndChangesNothing() {
        Target target = target(Role.VET, AccountStatus.ACTIVE, BRANCH);
        when(accounts.existsByEmail(NEW_EMAIL)).thenReturn(true);

        assertRule(() -> service.applyEmailChange(TARGET, new EmailChangeAttempt(41L, NEW_EMAIL, true)), "BR-TK-01",
                "Email đã được sử dụng (BR-TK-01)");
        verify(otps).settleCheckedOtp(TARGET, OtpPurpose.CHANGE_EMAIL, NEW_EMAIL, 41L, true);
        assertNoWriteAfterSettle(target);
    }

    @Test
    void applyMissingTargetIs404() {
        when(accounts.findByIdForUpdate(TARGET)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.applyEmailChange(TARGET, new EmailChangeAttempt(41L, NEW_EMAIL, true)))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(otps, notifications, audit);
    }

    // ================================================================ ranh giới transaction

    /**
     * #24 ghi phải giữ bộ đếm sai mã (docs/adr/0010): đúng một {@code noRollbackFor = OtpRejectedException}. #23 và bước
     * đọc không được có, bước đọc phải readOnly; không method nào {@code MANDATORY} (facade không có transaction).
     */
    @Test
    void transactionBoundariesMatchAdr0034() throws NoSuchMethodException {
        Transactional apply = method("applyEmailChange", Long.class, EmailChangeAttempt.class)
                .getAnnotation(Transactional.class);
        assertThat(apply.noRollbackFor()).containsExactly(OtpRejectedException.class);
        assertThat(apply.readOnly()).isFalse();

        Transactional issue = method("issueEmailChangeOtp", Long.class, String.class, PreparedOtp.class)
                .getAnnotation(Transactional.class);
        assertThat(issue.noRollbackFor()).isEmpty();
        assertThat(issue.readOnly()).isFalse();

        Transactional find = method("findEmailChangeCode", Long.class).getAnnotation(Transactional.class);
        assertThat(find.readOnly()).isTrue();
        assertThat(find.noRollbackFor()).isEmpty();

        for (Transactional tx : List.of(apply, issue, find)) {
            assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRED);
        }
    }

    // ================================================================ helpers

    private record Target(Account account, StaffProfile profile) {
    }

    private static Method method(String name, Class<?>... types) throws NoSuchMethodException {
        return StaffEmailChangeService.class.getMethod(name, types);
    }

    private void actAs(Role role, Long branchId) {
        when(branchScope.current()).thenReturn(new AccountPrincipal(ACTOR, "actor@petcare.test", 42L, role, branchId,
                false));
    }

    private Target target(Role role, AccountStatus status, Long branchId) {
        Account account = Account.createStaff(OLD_EMAIL, "0901234567", "hash", role);
        ReflectionTestUtils.setField(account, "id", TARGET);
        ReflectionTestUtils.setField(account, "status", status);
        StaffProfile profile = BeanUtils.instantiateClass(StaffProfile.class);
        ReflectionTestUtils.setField(profile, "accountId", TARGET);
        ReflectionTestUtils.setField(profile, "fullName", "Nhân viên 7");
        ReflectionTestUtils.setField(profile, "branchId", branchId);
        when(staffProfiles.findStaffRow(TARGET)).thenReturn(Optional.of(new StaffRow(TARGET, "Nhân viên 7", role,
                branchId, status, false, null)));
        when(accounts.findByIdForUpdate(TARGET)).thenReturn(Optional.of(account));
        when(staffProfiles.findById(TARGET)).thenReturn(Optional.of(profile));
        return new Target(account, profile);
    }

    private StaffDetailRow givenDetail(String email) {
        StaffDetailRow row = new StaffDetailRow(TARGET, email, "0901234567", "Nhân viên 7", Role.VET,
                AccountStatus.ACTIVE, false, BRANCH, null, null, true, null);
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

    private void assertNoWriteAfterSettle(Target target) {
        assertThat(target.account().getEmail()).isEqualTo(OLD_EMAIL);
        verify(accounts, never()).flush();
        verify(staffProfiles, never()).findStaffDetail(anyLong());
        verifyNoInteractions(notifications, audit);
        verify(otps, never()).issuePrepared(anyLong(), any(), anyString(), any(PreparedOtp.class));
    }

    private static void assertRule(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String ruleId,
            String message) {
        assertThatThrownBy(call).isInstanceOf(BusinessRuleViolationException.class).hasMessage(message)
                .hasMessageEndingWith("(" + ruleId + ")");
    }
}
