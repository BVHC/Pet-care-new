package com.petcare.module.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InOrder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.care.api.NotificationApi;
import com.petcare.module.care.api.NotificationApi.Channel;
import com.petcare.module.care.api.NotificationApi.NotificationRequest;
import com.petcare.module.identity.api.NotificationTemplateCode;
import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.entity.Account;
import com.petcare.module.identity.entity.AccountStatus;
import com.petcare.module.identity.entity.OtpPurpose;
import com.petcare.module.identity.exception.OtpRejectedException;
import com.petcare.module.identity.repository.AccountRepository;
import com.petcare.module.identity.repository.AccountResetState;
import com.petcare.module.identity.service.OtpService.ActiveOtp;
import com.petcare.module.identity.service.OtpService.IssuedOtp;
import com.petcare.module.identity.service.OtpService.PreparedOtp;
import com.petcare.module.identity.service.PasswordResetAttemptService.ResetAttempt;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.exception.BusinessRuleViolationException;

/**
 * Phần có transaction của UC04 (docs/adr/0023): chỉ tài khoản đủ điều kiện mới nhận mã (BR-TK-12); đặt lại dưới khóa
 * theo thứ tự khóa dòng → ghi kết quả mã → hash mới → hủy mọi phiên → email (BR-TK-13, docs/adr/0021); kiểm lại tài
 * khoản và hash dưới khóa; trùng mật khẩu hiện tại thì BR-TK-03 trước mọi lệnh ghi tài khoản.
 */
class PasswordResetAttemptServiceTest {

    private static final long ID = 7L;
    private static final long OTP_ID = 41L;
    private static final String EMAIL = "nv@petcare.test";
    private static final String CURRENT = "matkhau123";
    private static final String NEW = "matkhaumoi9";
    /** 09:00 giờ Việt Nam. */
    private static final Instant NOW = Instant.parse("2026-10-08T02:00:00Z");

    private final AccountRepository accounts = mock(AccountRepository.class);
    private final OtpService otps = mock(OtpService.class);
    private final SessionService sessions = mock(SessionService.class);
    private final NotificationApi notifications = mock(NotificationApi.class);
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final PasswordResetAttemptService service = new PasswordResetAttemptService(accounts, otps, sessions,
            notifications, encoder, Clock.fixed(NOW, TimeConfig.BUSINESS_ZONE));

    private String currentHash;
    private Account account;

    @BeforeEach
    void setUp() {
        currentHash = encoder.encode(CURRENT);
        account = Account.registerCustomer(EMAIL, currentHash, null);
        ReflectionTestUtils.setField(account, "id", ID);
        ReflectionTestUtils.setField(account, "role", Role.VET);
        ReflectionTestUtils.setField(account, "phone", "0900000000");
        ReflectionTestUtils.setField(account, "status", AccountStatus.ACTIVE);
        when(accounts.findByEmailForUpdate(EMAIL)).thenReturn(Optional.of(account));
        when(accounts.findByIdForUpdate(ID)).thenReturn(Optional.of(account));
    }

    // ---------------------------------------------------------------- quên mật khẩu

    @Test
    void eligibleAccountGetsPreparedCodeAndOtpEmail() {
        PreparedOtp prepared = new PreparedOtp("246810", "hash");
        when(otps.issuePrepared(ID, OtpPurpose.RESET_PASSWORD, EMAIL, prepared))
                .thenReturn(new IssuedOtp("246810", NOW.plusSeconds(300), NOW.plusSeconds(60), 5));

        assertThat(service.issueResetOtp(EMAIL, prepared)).isTrue();

        InOrder order = inOrder(accounts, otps, notifications);
        order.verify(accounts).findByEmailForUpdate(EMAIL);
        order.verify(otps).issuePrepared(ID, OtpPurpose.RESET_PASSWORD, EMAIL, prepared);
        order.verify(notifications).enqueue(new NotificationRequest(NotificationTemplateCode.OTP_PASSWORD_RESET,
                Channel.EMAIL, null, EMAIL, Map.of("ma_otp", "246810", "thoi_han_phut", 5), null));
    }

    @Test
    void temporarilyLockedAccountStillGetsCode() {
        ReflectionTestUtils.setField(account, "lockedUntil", NOW.plusSeconds(600));
        PreparedOtp prepared = new PreparedOtp("246810", "hash");
        when(otps.issuePrepared(ID, OtpPurpose.RESET_PASSWORD, EMAIL, prepared))
                .thenReturn(new IssuedOtp("246810", NOW.plusSeconds(300), NOW.plusSeconds(60), 5));

        assertThat(service.issueResetOtp(EMAIL, prepared)).as("BR-TK-12").isTrue();
    }

    @ParameterizedTest
    @CsvSource({"PENDING,false", "DISABLED,false", "ACTIVE,true"})
    void ineligibleAccountGetsNothing(AccountStatus status, boolean adminLocked) {
        ReflectionTestUtils.setField(account, "status", status);
        ReflectionTestUtils.setField(account, "locked", adminLocked);

        assertThat(service.issueResetOtp(EMAIL, new PreparedOtp("246810", "hash"))).isFalse();

        verify(otps, never()).issuePrepared(any(), any(), any(), any());
        verify(notifications, never()).enqueue(any());
    }

    @Test
    void unknownEmailGetsNothing() {
        when(accounts.findByEmailForUpdate("la@petcare.test")).thenReturn(Optional.empty());

        assertThat(service.issueResetOtp("la@petcare.test", new PreparedOtp("246810", "hash"))).isFalse();
        verify(notifications, never()).enqueue(any());
    }

    @Test
    void quotaViolationPropagatesWithoutEmail() {
        PreparedOtp prepared = new PreparedOtp("246810", "hash");
        doThrow(new BusinessRuleViolationException("BR-TK-07", "quá nhanh"))
                .when(otps).issuePrepared(ID, OtpPurpose.RESET_PASSWORD, EMAIL, prepared);

        assertThatThrownBy(() -> service.issueResetOtp(EMAIL, prepared)).hasMessageEndingWith("(BR-TK-07)");
        verify(notifications, never()).enqueue(any());
    }

    // ---------------------------------------------------------------- bước đọc không khóa

    @Test
    void candidateHasAccountHashAndActiveCode() {
        when(accounts.findResetStateByEmail(EMAIL))
                .thenReturn(Optional.of(new AccountResetState(ID, AccountStatus.ACTIVE, false, currentHash)));
        when(otps.findActive(ID, OtpPurpose.RESET_PASSWORD, EMAIL))
                .thenReturn(Optional.of(new ActiveOtp(OTP_ID, "code-hash", NOW.plusSeconds(60))));

        assertThat(service.findResetCandidate(EMAIL)).hasValueSatisfying(candidate -> {
            assertThat(candidate.accountId()).isEqualTo(ID);
            assertThat(candidate.otpId()).isEqualTo(OTP_ID);
            assertThat(candidate.codeHash()).isEqualTo("code-hash");
            assertThat(candidate.passwordHash()).isEqualTo(currentHash);
            assertThat(candidate.toString()).doesNotContain(currentHash).doesNotContain("code-hash");
        });
    }

    @Test
    void ineligibleAccountHasNoCandidateAndCodeIsNotRead() {
        when(accounts.findResetStateByEmail(EMAIL))
                .thenReturn(Optional.of(new AccountResetState(ID, AccountStatus.ACTIVE, true, currentHash)));

        assertThat(service.findResetCandidate(EMAIL)).isEmpty();
        verify(otps, never()).findActive(any(), any(), any());
    }

    @Test
    void noActiveCodeHasNoCandidate() {
        when(accounts.findResetStateByEmail(EMAIL))
                .thenReturn(Optional.of(new AccountResetState(ID, AccountStatus.ACTIVE, false, currentHash)));
        when(otps.findActive(ID, OtpPurpose.RESET_PASSWORD, EMAIL)).thenReturn(Optional.empty());

        assertThat(service.findResetCandidate(EMAIL)).isEmpty();
    }

    // ---------------------------------------------------------------- đặt lại dưới khóa

    @Test
    void successLocksSettlesResetsRevokesAllAndEmailsInThisOrder() {
        String newHash = encoder.encode(NEW);
        ReflectionTestUtils.setField(account, "mustChangePassword", true);
        ReflectionTestUtils.setField(account, "lockedUntil", NOW.plusSeconds(600));
        ReflectionTestUtils.setField(account, "failedLoginCount", 0);
        ReflectionTestUtils.setField(account, "lastSeenAt", NOW.minusSeconds(30));

        service.applyReset(attempt(true, currentHash, false, newHash));

        InOrder order = inOrder(accounts, otps, sessions, notifications);
        order.verify(accounts).findByIdForUpdate(ID);
        order.verify(otps).settleCheckedOtp(ID, OtpPurpose.RESET_PASSWORD, EMAIL, OTP_ID, true);
        order.verify(sessions).revokeAll(ID);
        order.verify(notifications).enqueue(new NotificationRequest(NotificationTemplateCode.PASSWORD_CHANGED,
                Channel.EMAIL, null, EMAIL, Map.of("thoi_diem", "09:00 08/10/2026"), null));
        assertThat(account.getPasswordHash()).isEqualTo(newHash);
        assertThat(account.isMustChangePassword()).isFalse();
        assertThat(account.getLockedUntil()).isNull();
        assertThat(account.getLastSeenAt()).isNull();
    }

    @ParameterizedTest
    @CsvSource({"ACTIVE,true", "DISABLED,false", "PENDING,false"})
    void accountNoLongerEligibleUnderLockIsGenericBrTk05BeforeAnyWrite(AccountStatus status, boolean adminLocked) {
        ReflectionTestUtils.setField(account, "status", status);
        ReflectionTestUtils.setField(account, "locked", adminLocked);

        assertGenericInvalidOtp();
    }

    @Test
    void accountGoneUnderLockIsGenericBrTk05() {
        when(accounts.findByIdForUpdate(ID)).thenReturn(Optional.empty());

        assertGenericInvalidOtp();
    }

    @Test
    void rejectedCodePropagatesWithoutTouchingAccount() {
        doThrow(new OtpRejectedException("BR-TK-05", OtpService.INVALID_OTP_MESSAGE))
                .when(otps).settleCheckedOtp(ID, OtpPurpose.RESET_PASSWORD, EMAIL, OTP_ID, false);

        assertThatThrownBy(() -> service.applyReset(attempt(false, currentHash, false, null)))
                .isExactlyInstanceOf(OtpRejectedException.class);
        assertThat(account.getPasswordHash()).isEqualTo(currentHash);
        verify(sessions, never()).revokeAll(anyLong());
        verify(notifications, never()).enqueue(any());
    }

    @Test
    void sameAsCurrentIsBrTk03AfterSettleAndBeforeAccountWrite() {
        assertThatThrownBy(() -> service.applyReset(attempt(true, currentHash, true, null)))
                .isExactlyInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("Mật khẩu mới không được trùng mật khẩu hiện tại (BR-TK-03)");

        verify(otps).settleCheckedOtp(ID, OtpPurpose.RESET_PASSWORD, EMAIL, OTP_ID, true);
        assertThat(account.getPasswordHash()).isEqualTo(currentHash);
        verify(sessions, never()).revokeAll(anyLong());
    }

    @Test
    void hashChangedSinceReadIsRecheckedUnderLockAndCanBecomeSame() {
        // Bước đọc thấy hash cũ (khác NEW); dưới khóa, mật khẩu vừa được đổi thành NEW từ phiên khác.
        String changedHash = encoder.encode(NEW);
        ReflectionTestUtils.setField(account, "passwordHash", changedHash);

        assertThatThrownBy(() -> service.applyReset(attempt(true, currentHash, false, encoder.encode(NEW))))
                .hasMessage("Mật khẩu mới không được trùng mật khẩu hiện tại (BR-TK-03)");
        assertThat(account.getPasswordHash()).isEqualTo(changedHash);
    }

    @Test
    void hashChangedSinceReadEncodesUnderLockWhenNoLongerSame() {
        // Bước đọc thấy NEW trùng mật khẩu hiện tại nên chưa mã hóa; dưới khóa mật khẩu đã đổi sang mật khẩu khác.
        ReflectionTestUtils.setField(account, "passwordHash", encoder.encode("khac12345"));

        service.applyReset(attempt(true, currentHash, true, null));

        assertThat(encoder.matches(NEW, account.getPasswordHash())).isTrue();
        verify(sessions).revokeAll(ID);
    }

    private void assertGenericInvalidOtp() {
        assertThatThrownBy(() -> service.applyReset(attempt(true, currentHash, false, "new-hash")))
                .isExactlyInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("OTP không đúng hoặc đã hết hạn (BR-TK-05)");
        verify(otps, never()).settleCheckedOtp(any(), any(), any(), any(), anyBoolean());
        verify(sessions, never()).revokeAll(anyLong());
        assertThat(account.getPasswordHash()).isEqualTo(currentHash);
    }

    private static ResetAttempt attempt(boolean otpMatched, String readHash, boolean sameAsCurrent, String newHash) {
        return new ResetAttempt(ID, OTP_ID, otpMatched, readHash, sameAsCurrent, NEW, newHash);
    }
}
