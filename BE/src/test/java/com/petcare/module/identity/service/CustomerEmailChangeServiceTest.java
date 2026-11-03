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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.care.api.NotificationApi;
import com.petcare.module.care.api.NotificationApi.Channel;
import com.petcare.module.care.api.NotificationApi.NotificationRequest;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi.CustomerContact;
import com.petcare.module.identity.api.NotificationTemplateCode;
import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.dto.OtpSentResponse;
import com.petcare.module.identity.entity.Account;
import com.petcare.module.identity.entity.AccountStatus;
import com.petcare.module.identity.entity.OtpPurpose;
import com.petcare.module.identity.exception.CustomerAccountNotEditableException;
import com.petcare.module.identity.exception.OtpRejectedException;
import com.petcare.module.identity.mapper.StaffAdminMapper;
import com.petcare.module.identity.repository.AccountRepository;
import com.petcare.module.identity.service.OtpService.AccountBoundOtp;
import com.petcare.module.identity.service.OtpService.IssuedOtp;
import com.petcare.module.identity.service.OtpService.PreparedOtp;
import com.petcare.module.identity.service.StaffEmailChangeService.EmailChangeAttempt;
import com.petcare.module.identity.service.StaffEmailChangeService.EmailChangeCode;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;

/**
 * UC22 — lễ tân sửa hộ email tài khoản khách (identity-v1 #25, #26; BR-TK-16). Thứ tự:
 * <ul>
 *   <li>#25: hồ sơ (404; chưa gắn tài khoản → 400 không mã BR) → khóa {@code accounts} → không phải tài khoản khách (404)
 *       / {@code PENDING} (400 không mã BR) → trùng email hiện tại / tài khoản khác (BR-TK-01) → {@code issuePrepared}
 *       (BR-TK-07 bên trong) → outbox.</li>
 *   <li>#26 đọc: cùng các kiểm trên, không khóa → mã còn mở; #26 ghi: khóa → kiểm lại → settle (lệnh ghi đầu tiên) →
 *       BR-TK-01 → đổi email → 2 thông báo → audit → flush.</li>
 * </ul>
 * Mỗi nhánh lỗi không gọi lệnh ghi nào đứng sau nó. Mapper thật; {@code OtpService}, {@code CustomerQueryApi} mock.
 */
class CustomerEmailChangeServiceTest {

    private static final long CUSTOMER = 7L;
    private static final long ACCOUNT = 70L;
    private static final String OLD_EMAIL = "khach7@petcare.test";
    private static final String NEW_EMAIL = "moi7@petcare.test";
    private static final Instant NOW = Instant.parse("2026-10-12T03:04:00Z");
    private static final PreparedOtp PREPARED = new PreparedOtp("123456", "$2a$otp");
    private static final IssuedOtp ISSUED = new IssuedOtp("123456", NOW.plusSeconds(300), NOW.plusSeconds(60), 5);
    private static final String NOT_LINKED = "Hồ sơ chưa gắn tài khoản online nên không có email tài khoản để sửa";
    private static final String PENDING = "Tài khoản chưa xác thực email nên chưa sửa được email";

    private final CustomerQueryApi customers = mock(CustomerQueryApi.class);
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final OtpService otps = mock(OtpService.class);
    private final NotificationApi notifications = mock(NotificationApi.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);
    private final StaffAdminMapper mapper = Mappers.getMapper(StaffAdminMapper.class);
    private final CustomerEmailChangeService service = new CustomerEmailChangeService(customers, accounts, otps,
            notifications, audit, mapper, Clock.fixed(NOW, ZoneOffset.UTC));

    // ================================================================ #25 gửi mã

    @Test
    void issueLooksUpProfileThenLocksThenGuardsThenIssuesCodeToNormalizedNewEmailAndQueuesOtpMail() {
        linkedCustomer(Role.CUSTOMER, AccountStatus.ACTIVE);
        when(otps.issuePrepared(anyLong(), any(), anyString(), any(PreparedOtp.class))).thenReturn(ISSUED);

        OtpSentResponse response = service.issueEmailChangeOtp(CUSTOMER, "  Moi7@PetCare.TEST ", PREPARED);

        assertThat(response).isEqualTo(new OtpSentResponse(NOW.plusSeconds(60), null, 6));
        InOrder order = inOrder(customers, accounts, otps, notifications);
        order.verify(customers).findContact(CUSTOMER);
        order.verify(accounts).findByIdForUpdate(ACCOUNT);
        order.verify(accounts).existsByEmail(NEW_EMAIL);
        order.verify(otps).issuePrepared(ACCOUNT, OtpPurpose.CHANGE_EMAIL, NEW_EMAIL, PREPARED);
        order.verify(notifications).enqueue(new NotificationRequest(NotificationTemplateCode.OTP_EMAIL_CHANGE,
                Channel.EMAIL, null, NEW_EMAIL, Map.of("ma_otp", "123456", "thoi_han_phut", 5), null));
        verifyNoInteractions(audit);
        verify(accounts, never()).flush();
    }

    /** Khôi phục tài khoản: đang bị khóa vẫn gửi được; trạng thái, khóa, email giữ nguyên. */
    @Test
    void issueWorksForLockedActiveAccountAndKeepsItUntouched() {
        Account account = linkedCustomer(Role.CUSTOMER, AccountStatus.ACTIVE);
        ReflectionTestUtils.setField(account, "locked", true);
        when(otps.issuePrepared(anyLong(), any(), anyString(), any(PreparedOtp.class))).thenReturn(ISSUED);

        service.issueEmailChangeOtp(CUSTOMER, NEW_EMAIL, PREPARED);

        verify(otps).issuePrepared(ACCOUNT, OtpPurpose.CHANGE_EMAIL, NEW_EMAIL, PREPARED);
        assertThat(account.getEmail()).isEqualTo(OLD_EMAIL);
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.isLocked()).isTrue();
    }

    @Test
    void issueUnknownProfileIs404BeforeAnythingElse() {
        when(customers.findContact(CUSTOMER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.issueEmailChangeOtp(CUSTOMER, NEW_EMAIL, PREPARED))
                .isInstanceOf(ResourceNotFoundException.class).hasMessage("Không tìm thấy hồ sơ khách #7");

        verifyNoInteractions(accounts, otps, notifications, audit);
    }

    /** Hồ sơ COUNTER chưa gắn tài khoản: 400 không mã BR, trước khi khóa hay đụng OTP. */
    @Test
    void issueUnlinkedProfileIs400WithoutRuleCode() {
        when(customers.findContact(CUSTOMER)).thenReturn(Optional.of(contact(null)));

        assertNotEditable(() -> service.issueEmailChangeOtp(CUSTOMER, NEW_EMAIL, PREPARED), NOT_LINKED);

        verifyNoInteractions(accounts, otps, notifications, audit);
    }

    /** Dữ liệu hỏng (hồ sơ trỏ tới tài khoản nhân viên / tài khoản không còn): 404, lễ tân không sửa được email nhân viên. */
    @Test
    void issueMissingOrStaffAccountIs404AndNeverIssuesACode() {
        when(customers.findContact(CUSTOMER)).thenReturn(Optional.of(contact(ACCOUNT)));
        when(accounts.findByIdForUpdate(ACCOUNT)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.issueEmailChangeOtp(CUSTOMER, NEW_EMAIL, PREPARED))
                .isInstanceOf(ResourceNotFoundException.class);

        Account staff = Account.createStaff(OLD_EMAIL, "0901234567", "hash", Role.VET);
        ReflectionTestUtils.setField(staff, "id", ACCOUNT);
        when(accounts.findByIdForUpdate(ACCOUNT)).thenReturn(Optional.of(staff));
        assertThatThrownBy(() -> service.issueEmailChangeOtp(CUSTOMER, NEW_EMAIL, PREPARED))
                .isInstanceOf(ResourceNotFoundException.class).hasMessage("Không tìm thấy hồ sơ khách #7");

        verify(accounts, never()).existsByEmail(any());
        verifyNoInteractions(otps, notifications, audit);
    }

    /** {@link Account#changeEmail} ném lỗi lập trình với PENDING, nên guard phải chặn trước: 400 không mã BR. */
    @Test
    void issuePendingAccountIs400WithoutRuleCode() {
        linkedCustomer(Role.CUSTOMER, AccountStatus.PENDING);

        assertNotEditable(() -> service.issueEmailChangeOtp(CUSTOMER, NEW_EMAIL, PREPARED), PENDING);

        verify(accounts, never()).existsByEmail(any());
        verifyNoInteractions(otps, notifications, audit);
    }

    /** Trùng email hiện tại (sau chuẩn hóa) → BR-TK-01 message riêng, không gửi mã, không tiêu quota. */
    @Test
    void issueSameAsCurrentEmailIsBrTk01WithoutCode() {
        linkedCustomer(Role.CUSTOMER, AccountStatus.ACTIVE);

        assertRule(() -> service.issueEmailChangeOtp(CUSTOMER, " KHACH7@petcare.test", PREPARED), "BR-TK-01",
                "Email mới trùng email hiện tại (BR-TK-01)");

        verify(accounts, never()).existsByEmail(any());
        verifyNoInteractions(otps, notifications, audit);
    }

    @Test
    void issueEmailOfAnotherAccountIsBrTk01WithoutCode() {
        linkedCustomer(Role.CUSTOMER, AccountStatus.ACTIVE);
        when(accounts.existsByEmail(NEW_EMAIL)).thenReturn(true);

        assertRule(() -> service.issueEmailChangeOtp(CUSTOMER, NEW_EMAIL, PREPARED), "BR-TK-01",
                "Email đã được sử dụng (BR-TK-01)");

        verifyNoInteractions(otps, notifications, audit);
    }

    @Test
    void issueQuotaRejectionQueuesNoMail() {
        linkedCustomer(Role.CUSTOMER, AccountStatus.ACTIVE);
        BusinessRuleViolationException quota = new BusinessRuleViolationException("BR-TK-07", "chờ 30 giây");
        when(otps.issuePrepared(anyLong(), any(), anyString(), any(PreparedOtp.class))).thenThrow(quota);

        assertThatThrownBy(() -> service.issueEmailChangeOtp(CUSTOMER, NEW_EMAIL, PREPARED)).isSameAs(quota);

        verifyNoInteractions(notifications, audit);
    }

    // ================================================================ #26 bước đọc

    @Test
    void findReturnsOpenCodeWithItsTargetEmailWithoutLocking() {
        linkedCustomer(Role.CUSTOMER, AccountStatus.ACTIVE);
        when(accounts.findById(ACCOUNT)).thenReturn(Optional.of(account(Role.CUSTOMER, AccountStatus.ACTIVE)));
        when(otps.findActiveForAccount(ACCOUNT, OtpPurpose.CHANGE_EMAIL))
                .thenReturn(Optional.of(new AccountBoundOtp(41L, NEW_EMAIL, "$2a$code")));

        assertThat(service.findEmailChangeCode(CUSTOMER)).contains(new EmailChangeCode(41L, NEW_EMAIL, "$2a$code"));

        verify(accounts, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void findWithoutCodeIsEmpty() {
        when(customers.findContact(CUSTOMER)).thenReturn(Optional.of(contact(ACCOUNT)));
        when(accounts.findById(ACCOUNT)).thenReturn(Optional.of(account(Role.CUSTOMER, AccountStatus.ACTIVE)));
        when(otps.findActiveForAccount(ACCOUNT, OtpPurpose.CHANGE_EMAIL)).thenReturn(Optional.empty());

        assertThat(service.findEmailChangeCode(CUSTOMER)).isEmpty();
    }

    /** 404 / 400 như #25 xảy ra trước khi tra mã: không tra mã của hồ sơ không hợp lệ. */
    @Test
    void findRejectsUnknownUnlinkedStaffAndPendingBeforeCodeLookup() {
        when(customers.findContact(CUSTOMER)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.findEmailChangeCode(CUSTOMER)).isInstanceOf(ResourceNotFoundException.class);

        when(customers.findContact(CUSTOMER)).thenReturn(Optional.of(contact(null)));
        assertNotEditable(() -> service.findEmailChangeCode(CUSTOMER), NOT_LINKED);

        when(customers.findContact(CUSTOMER)).thenReturn(Optional.of(contact(ACCOUNT)));
        when(accounts.findById(ACCOUNT)).thenReturn(Optional.of(account(Role.VET, AccountStatus.ACTIVE)));
        assertThatThrownBy(() -> service.findEmailChangeCode(CUSTOMER)).isInstanceOf(ResourceNotFoundException.class);

        when(accounts.findById(ACCOUNT)).thenReturn(Optional.of(account(Role.CUSTOMER, AccountStatus.PENDING)));
        assertNotEditable(() -> service.findEmailChangeCode(CUSTOMER), PENDING);

        verifyNoInteractions(otps);
    }

    // ================================================================ #26 bước ghi

    @Test
    void applyLocksGuardsSettlesFirstThenChangesEmailNotifiesBothAuditsAndFlushes() {
        Account account = linkedCustomer(Role.CUSTOMER, AccountStatus.ACTIVE);

        service.applyEmailChange(CUSTOMER, new EmailChangeAttempt(41L, NEW_EMAIL, true));

        assertThat(account.getEmail()).isEqualTo(NEW_EMAIL);
        InOrder order = inOrder(customers, accounts, otps, notifications, audit);
        order.verify(customers).findContact(CUSTOMER);
        order.verify(accounts).findByIdForUpdate(ACCOUNT);
        order.verify(otps).settleCheckedOtp(ACCOUNT, OtpPurpose.CHANGE_EMAIL, NEW_EMAIL, 41L, true);
        order.verify(accounts).existsByEmail(NEW_EMAIL);
        Map<String, Object> payload = Map.of("email_cu", OLD_EMAIL, "email_moi", NEW_EMAIL,
                "thoi_diem", "10:04 12/10/2026");
        order.verify(notifications).enqueue(new NotificationRequest(NotificationTemplateCode.EMAIL_CHANGED_NOTICE,
                Channel.EMAIL, null, OLD_EMAIL, payload, null));
        order.verify(notifications).enqueue(new NotificationRequest(NotificationTemplateCode.EMAIL_CHANGED_NOTICE,
                Channel.EMAIL, null, NEW_EMAIL, payload, null));
        order.verify(audit).record(any());
        order.verify(accounts).flush();

        AuditEntry entry = capturedAudit();
        assertThat(entry.action()).isEqualTo(IdentityAuditActions.CUSTOMER_EMAIL_CHANGED);
        assertThat(entry.entityType()).isEqualTo("accounts");
        assertThat(entry.entityId()).as("entity là tài khoản, không phải hồ sơ khách").isEqualTo(ACCOUNT);
        assertThat(snapshot(entry.before())).containsExactly(Map.entry("email", OLD_EMAIL));
        assertThat(snapshot(entry.after())).containsExactly(Map.entry("email", NEW_EMAIL),
                Map.entry("verificationMethod", "ID_CARD_IN_PERSON"));
    }

    /** Đổi email chỉ đổi {@code email}: trạng thái, khóa, mật khẩu giữ nguyên (docs/adr/0034 Q1, Q2). */
    @Test
    void applyKeepsStatusLockAndPassword() {
        Account account = linkedCustomer(Role.CUSTOMER, AccountStatus.ACTIVE);
        ReflectionTestUtils.setField(account, "locked", true);

        service.applyEmailChange(CUSTOMER, new EmailChangeAttempt(41L, NEW_EMAIL, true));

        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.isLocked()).isTrue();
        assertThat(account.getPasswordHash()).isEqualTo("hash");
    }

    @Test
    void applyRejectedCodeStopsBeforeAnyOtherWrite() {
        Account account = linkedCustomer(Role.CUSTOMER, AccountStatus.ACTIVE);
        OtpRejectedException wrong = new OtpRejectedException("BR-TK-05", OtpService.INVALID_OTP_MESSAGE);
        doThrow(wrong).when(otps).settleCheckedOtp(anyLong(), any(), anyString(), anyLong(), anyBoolean());

        assertThatThrownBy(() -> service.applyEmailChange(CUSTOMER, new EmailChangeAttempt(41L, NEW_EMAIL, false)))
                .isSameAs(wrong);

        verify(accounts, never()).existsByEmail(any());
        assertNoWriteAfterSettle(account);
    }

    /** Email vừa bị tài khoản khác dùng: BR-TK-01 sau settle → rollback cả {@code consumed_at} (transaction thật). */
    @Test
    void applyEmailTakenMeanwhileIsBrTk01AfterSettleAndChangesNothing() {
        Account account = linkedCustomer(Role.CUSTOMER, AccountStatus.ACTIVE);
        when(accounts.existsByEmail(NEW_EMAIL)).thenReturn(true);

        assertRule(() -> service.applyEmailChange(CUSTOMER, new EmailChangeAttempt(41L, NEW_EMAIL, true)), "BR-TK-01",
                "Email đã được sử dụng (BR-TK-01)");

        verify(otps).settleCheckedOtp(ACCOUNT, OtpPurpose.CHANGE_EMAIL, NEW_EMAIL, 41L, true);
        assertNoWriteAfterSettle(account);
    }

    /** Hồ sơ / tài khoản không hợp lệ dưới khóa: dừng trước {@code settle}, không đụng bộ đếm của mã. */
    @ParameterizedTest(name = "role={0} status={1}")
    @CsvSource({"VET, ACTIVE", "CUSTOMER, PENDING"})
    void applyInvalidAccountNeverTouchesTheCode(Role role, AccountStatus status) {
        linkedCustomer(role, status);

        assertThatThrownBy(() -> service.applyEmailChange(CUSTOMER, new EmailChangeAttempt(41L, NEW_EMAIL, true)))
                .isInstanceOfAny(ResourceNotFoundException.class, CustomerAccountNotEditableException.class);

        verifyNoInteractions(otps, notifications, audit);
        verify(accounts, never()).flush();
    }

    @Test
    void applyUnknownOrUnlinkedProfileNeverLocksOrTouchesTheCode() {
        when(customers.findContact(CUSTOMER)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.applyEmailChange(CUSTOMER, new EmailChangeAttempt(41L, NEW_EMAIL, true)))
                .isInstanceOf(ResourceNotFoundException.class);

        when(customers.findContact(CUSTOMER)).thenReturn(Optional.of(contact(null)));
        assertNotEditable(() -> service.applyEmailChange(CUSTOMER, new EmailChangeAttempt(41L, NEW_EMAIL, true)),
                NOT_LINKED);

        verifyNoInteractions(accounts, otps, notifications, audit);
    }

    // ================================================================ ranh giới transaction

    /**
     * #26 ghi phải giữ bộ đếm sai mã (docs/adr/0010): đúng một {@code noRollbackFor = OtpRejectedException}. #25 và bước
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

    private static Method method(String name, Class<?>... types) throws NoSuchMethodException {
        return CustomerEmailChangeService.class.getMethod(name, types);
    }

    private static CustomerContact contact(Long accountId) {
        return new CustomerContact(CUSTOMER, "Khách 7", "0912345678", OLD_EMAIL, accountId, false);
    }

    private static Account account(Role role, AccountStatus status) {
        Account account = role == Role.CUSTOMER
                ? Account.registerCustomer(OLD_EMAIL, "hash", NOW.plusSeconds(3600))
                : Account.createStaff(OLD_EMAIL, "0901234567", "hash", role);
        ReflectionTestUtils.setField(account, "id", ACCOUNT);
        ReflectionTestUtils.setField(account, "status", status);
        return account;
    }

    /** Hồ sơ {@code CUSTOMER} gắn tài khoản {@code ACCOUNT} có {@code role} / {@code status} cho trước, khóa được dòng. */
    private Account linkedCustomer(Role role, AccountStatus status) {
        Account account = account(role, status);
        when(customers.findContact(CUSTOMER)).thenReturn(Optional.of(contact(ACCOUNT)));
        when(accounts.findByIdForUpdate(ACCOUNT)).thenReturn(Optional.of(account));
        return account;
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

    private void assertNoWriteAfterSettle(Account account) {
        assertThat(account.getEmail()).isEqualTo(OLD_EMAIL);
        verify(accounts, never()).flush();
        verifyNoInteractions(notifications, audit);
        verify(otps, never()).issuePrepared(anyLong(), any(), anyString(), any(PreparedOtp.class));
    }

    private static void assertNotEditable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String message) {
        assertThatThrownBy(call).isInstanceOf(CustomerAccountNotEditableException.class).hasMessage(message)
                .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain("BR-"));
    }

    private static void assertRule(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String ruleId,
            String message) {
        assertThatThrownBy(call).isInstanceOf(BusinessRuleViolationException.class).hasMessage(message)
                .hasMessageEndingWith("(" + ruleId + ")");
    }
}
