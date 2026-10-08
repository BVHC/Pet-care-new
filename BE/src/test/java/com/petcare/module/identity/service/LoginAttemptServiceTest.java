package com.petcare.module.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.care.api.NotificationApi;
import com.petcare.module.care.api.NotificationApi.Channel;
import com.petcare.module.care.api.NotificationApi.NotificationRequest;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi.CustomerContact;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.NotificationTemplateCode;
import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.identity.dto.LoginResponse;
import com.petcare.module.identity.entity.Account;
import com.petcare.module.identity.entity.AccountStatus;
import com.petcare.module.identity.exception.InvalidCredentialsException;
import com.petcare.module.identity.exception.LoginRejectedException;
import com.petcare.module.identity.mapper.LoginMapper;
import com.petcare.module.identity.repository.AccountCredential;
import com.petcare.module.identity.repository.AccountRepository;
import com.petcare.module.identity.service.LoginAttemptService.LoginCounterSnapshot;
import com.petcare.module.identity.service.SessionService.OpenedSession;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.exception.ErrorCode;

/**
 * UC03 dưới khóa (docs/adr/0019): thứ tự tồn tại → khóa tạm → mật khẩu → trạng thái (BR-TK-08, 09, 10, 11), bộ đếm
 * và email cảnh báo (BR-TK-09, ST01), audit từng nhánh (BR-QT-15), nhánh 400 ném trước mọi lệnh ghi.
 */
class LoginAttemptServiceTest {

    private static final long ID = 7L;
    private static final String EMAIL = "nv@petcare.test";
    private static final String PASSWORD = "matkhau123";
    private static final String IP = "10.0.0.1";
    private static final String UA = "JUnit";
    /** 09:00 giờ Việt Nam. */
    private static final Instant NOW = Instant.parse("2026-10-08T02:00:00Z");

    private final AccountRepository accounts = mock(AccountRepository.class);
    private final SessionService sessions = mock(SessionService.class);
    private final CustomerQueryApi customers = mock(CustomerQueryApi.class);
    private final NotificationApi notifications = mock(NotificationApi.class);
    private final SystemConfigApi configs = mock(SystemConfigApi.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final LoginAttemptService service = new LoginAttemptService(accounts, sessions, customers, notifications,
            configs, audit, encoder, Mappers.getMapper(LoginMapper.class), Clock.fixed(NOW, TimeConfig.BUSINESS_ZONE));

    private String hash;

    @BeforeEach
    void setUp() {
        hash = encoder.encode(PASSWORD);
        when(configs.getInt(ConfigKey.LOGIN_MAX_FAILED_ATTEMPTS)).thenReturn(5);
        when(configs.getInt(ConfigKey.LOGIN_FAILED_WINDOW_MINUTES)).thenReturn(15);
        when(configs.getInt(ConfigKey.LOGIN_LOCK_MINUTES)).thenReturn(15);
        when(sessions.open(anyLong(), any(), any()))
                .thenReturn(new OpenedSession(99L, "jwt-token", NOW.plus(Duration.ofHours(12))));
    }

    // ---------------------------------------------------------------- tồn tại (BR-TK-10)

    @Test
    void unknownEmailRecordsAuditThenThrows401() {
        assertThatThrownBy(() -> service.rejectUnknownEmail("la@petcare.test"))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage(InvalidCredentialsException.MESSAGE)
                .satisfies(ex -> assertThat(((InvalidCredentialsException) ex).errorCode())
                        .isEqualTo(ErrorCode.UNAUTHENTICATED));

        AuditEntry entry = recordedEntry();
        assertThat(entry.action()).isEqualTo(IdentityAuditActions.LOGIN_FAILED);
        assertThat(entry.reason()).isEqualTo(IdentityAuditActions.REASON_UNKNOWN_EMAIL);
        assertThat(entry.actorAccountId()).isNull();
        assertThat(entry.actorEmail()).isEqualTo("la@petcare.test");
        assertThat(entry.entityType()).isNull();
        verifyNoInteractions(accounts, sessions, notifications);
    }

    @Test
    void accountDeletedBetweenReadAndLockIsTreatedAsUnknownEmail() {
        when(accounts.findByIdForUpdate(ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> login(PASSWORD, true)).isInstanceOf(InvalidCredentialsException.class);

        AuditEntry entry = recordedEntry();
        assertThat(entry.reason()).isEqualTo(IdentityAuditActions.REASON_UNKNOWN_EMAIL);
        assertThat(entry.actorAccountId()).isNull();
        verifyNoInteractions(sessions, notifications);
    }

    // ---------------------------------------------------------------- sai mật khẩu (BR-TK-09)

    @Test
    void wrongPasswordCountsAndAuditsBeforeAfterThen401() {
        Account account = staff(AccountStatus.ACTIVE);
        lockable(account);

        assertThatThrownBy(() -> login("sai12345", false)).isInstanceOf(InvalidCredentialsException.class);

        assertThat(account.getFailedLoginCount()).isEqualTo(1);
        assertThat(account.getFirstFailedLoginAt()).isEqualTo(NOW);
        AuditEntry entry = recordedEntry();
        assertThat(entry.action()).isEqualTo(IdentityAuditActions.LOGIN_FAILED);
        assertThat(entry.reason()).isEqualTo(IdentityAuditActions.REASON_BAD_CREDENTIALS);
        assertThat(entry.entityType()).isEqualTo("accounts");
        assertThat(entry.entityId()).isEqualTo(ID);
        assertThat(entry.actorAccountId()).isEqualTo(ID);
        assertThat(entry.actorEmail()).isEqualTo(EMAIL);
        assertThat(entry.before()).isEqualTo(new LoginCounterSnapshot(0, null));
        assertThat(entry.after()).isEqualTo(new LoginCounterSnapshot(1, null));
        verifyNoInteractions(sessions, notifications, customers);
    }

    @Test
    void fifthWrongPasswordLocksAndEnqueuesOneWarning() {
        Account account = staff(AccountStatus.ACTIVE);
        ReflectionTestUtils.setField(account, "failedLoginCount", 4);
        ReflectionTestUtils.setField(account, "firstFailedLoginAt", NOW.minus(Duration.ofMinutes(10)));
        lockable(account);

        assertThatThrownBy(() -> login("sai12345", false)).isInstanceOf(InvalidCredentialsException.class);

        Instant until = NOW.plus(Duration.ofMinutes(15));
        assertThat(account.getLockedUntil()).isEqualTo(until);
        assertThat(account.getFailedLoginCount()).isZero();
        ArgumentCaptor<NotificationRequest> sent = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notifications).enqueue(sent.capture());
        assertThat(sent.getValue().templateCode()).isEqualTo(NotificationTemplateCode.LOGIN_LOCKED_WARNING);
        assertThat(sent.getValue().channel()).isEqualTo(Channel.EMAIL);
        assertThat(sent.getValue().recipientEmail()).isEqualTo(EMAIL);
        assertThat(sent.getValue().recipientAccountId()).as("có thể là tài khoản PENDING").isNull();
        assertThat(sent.getValue().payload())
                .isEqualTo(Map.of("so_lan_sai", 5, "thoi_diem_mo_khoa", "09:15 08/10/2026"));
        AuditEntry entry = recordedEntry();
        assertThat(entry.before()).isEqualTo(new LoginCounterSnapshot(4, null));
        assertThat(entry.after()).isEqualTo(new LoginCounterSnapshot(0, until));
    }

    @Test
    void pendingAccountWithWrongPasswordIsCountedLikeAnyOther401() {
        Account account = customer(AccountStatus.PENDING);
        lockable(account);

        assertThatThrownBy(() -> login("sai12345", false)).isInstanceOf(InvalidCredentialsException.class);

        assertThat(account.getFailedLoginCount()).isEqualTo(1);
        assertThat(recordedEntry().reason()).isEqualTo(IdentityAuditActions.REASON_BAD_CREDENTIALS);
    }

    // ---------------------------------------------------------------- đang khóa tạm (BR-TK-09)

    @Test
    void wrongPasswordWhileLockedIsNotCounted() {
        Account account = staff(AccountStatus.ACTIVE);
        Instant until = NOW.plus(Duration.ofMinutes(3));
        ReflectionTestUtils.setField(account, "lockedUntil", until);
        lockable(account);

        assertThatThrownBy(() -> login("sai12345", false)).isInstanceOf(InvalidCredentialsException.class);

        assertThat(account.getFailedLoginCount()).isZero();
        assertThat(account.getLockedUntil()).isEqualTo(until);
        assertThat(recordedEntry().reason()).isEqualTo(IdentityAuditActions.REASON_TEMPORARILY_LOCKED);
        verifyNoInteractions(notifications, sessions);
    }

    @Test
    void correctPasswordWhileLockedIs400WithRetryTimeAndNoWrite() {
        Account account = staff(AccountStatus.ACTIVE);
        ReflectionTestUtils.setField(account, "lockedUntil", NOW.plusSeconds(200));   // 09:03:20 → hiển thị 09:04
        lockable(account);

        assertThatThrownBy(() -> login(PASSWORD, true))
                .isInstanceOf(LoginRejectedException.class)
                .hasMessage("Tài khoản tạm khóa do đăng nhập sai nhiều lần. Vui lòng thử lại sau 09:04 08/10/2026"
                        + " (BR-TK-09)")
                .satisfies(ex -> assertThat(((LoginRejectedException) ex).auditEntry().reason())
                        .isEqualTo(IdentityAuditActions.REASON_TEMPORARILY_LOCKED));

        assertNothingWritten(account);
    }

    @Test
    void lockEndingExactlyNowNoLongerBlocks() {
        Account account = staff(AccountStatus.ACTIVE);
        ReflectionTestUtils.setField(account, "lockedUntil", NOW);
        lockable(account);

        LoginResponse response = login(PASSWORD, true);

        assertThat(response.accessToken()).isEqualTo("jwt-token");
        assertThat(account.getLockedUntil()).isNull();
    }

    @Test
    void pendingAndTemporarilyLockedReportsBrTk09First() {
        Account account = customer(AccountStatus.PENDING);
        ReflectionTestUtils.setField(account, "lockedUntil", NOW.plusSeconds(60));
        lockable(account);

        assertThatThrownBy(() -> login(PASSWORD, true))
                .isInstanceOf(LoginRejectedException.class)
                .hasMessageEndingWith("(BR-TK-09)");
    }

    // ---------------------------------------------------------------- trạng thái (BR-TK-08, 11)

    @Test
    void pendingWithCorrectPasswordIs400BrTk08AndNoWrite() {
        Account account = customer(AccountStatus.PENDING);
        ReflectionTestUtils.setField(account, "failedLoginCount", 2);
        ReflectionTestUtils.setField(account, "firstFailedLoginAt", NOW.minusSeconds(30));
        lockable(account);

        assertThatThrownBy(() -> login(PASSWORD, true))
                .isInstanceOf(LoginRejectedException.class)
                .hasMessage("Tài khoản chưa xác thực email. Vui lòng nhập mã OTP đã gửi tới email (BR-TK-08)")
                .satisfies(ex -> {
                    AuditEntry entry = ((LoginRejectedException) ex).auditEntry();
                    assertThat(entry.action()).isEqualTo(IdentityAuditActions.LOGIN_FAILED);
                    assertThat(entry.reason()).isEqualTo(IdentityAuditActions.REASON_PENDING);
                    assertThat(entry.entityId()).isEqualTo(ID);
                    assertThat(entry.actorAccountId()).isEqualTo(ID);
                    assertThat(entry.before()).isEqualTo(new LoginCounterSnapshot(2, null));
                });

        assertThat(account.getFailedLoginCount()).as("không reset khi bị chặn").isEqualTo(2);
        assertNothingWritten(account);
    }

    @Test
    void lockedAccountIs400BrTk11() {
        Account account = staff(AccountStatus.ACTIVE);
        ReflectionTestUtils.setField(account, "locked", true);
        lockable(account);

        assertThatThrownBy(() -> login(PASSWORD, true))
                .isInstanceOf(LoginRejectedException.class)
                .hasMessage("Tài khoản đã bị khóa. Vui lòng liên hệ Pet Care để được hỗ trợ (BR-TK-11)")
                .satisfies(ex -> assertThat(((LoginRejectedException) ex).auditEntry().reason())
                        .isEqualTo(IdentityAuditActions.REASON_LOCKED));
        assertNothingWritten(account);
    }

    @Test
    void disabledStaffIs400BrTk11() {
        Account account = staff(AccountStatus.DISABLED);
        lockable(account);

        assertThatThrownBy(() -> login(PASSWORD, true))
                .isInstanceOf(LoginRejectedException.class)
                .hasMessageEndingWith("(BR-TK-11)")
                .satisfies(ex -> assertThat(((LoginRejectedException) ex).auditEntry().reason())
                        .isEqualTo(IdentityAuditActions.REASON_DISABLED));
        assertNothingWritten(account);
    }

    @Test
    void lockedAndDisabledReportsLockedReason() {
        Account account = staff(AccountStatus.DISABLED);
        ReflectionTestUtils.setField(account, "locked", true);
        lockable(account);

        assertThatThrownBy(() -> login(PASSWORD, true))
                .satisfies(ex -> assertThat(((LoginRejectedException) ex).auditEntry().reason())
                        .isEqualTo(IdentityAuditActions.REASON_LOCKED));
    }

    // ---------------------------------------------------------------- thành công

    @Test
    void staffSuccessOpensSessionAuditsAndSkipsCustomer() {
        Account account = staff(AccountStatus.ACTIVE);
        ReflectionTestUtils.setField(account, "mustChangePassword", true);
        lockable(account);

        LoginResponse response = login(PASSWORD, true);

        verify(sessions).open(ID, IP, UA);
        verifyNoInteractions(customers, notifications);
        assertThat(response.accessToken()).isEqualTo("jwt-token");
        assertThat(response.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(12)));
        assertThat(response.linkDecisionPending()).isFalse();
        assertThat(response.account().id()).isEqualTo(ID);
        assertThat(response.account().email()).isEqualTo(EMAIL);
        assertThat(response.account().role()).isEqualTo(Role.RECEPTIONIST);
        assertThat(response.account().status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(response.account().isLocked()).isFalse();
        assertThat(response.account().mustChangePassword()).as("BR-TK-17 vẫn đăng nhập được").isTrue();

        AuditEntry entry = recordedEntry();
        assertThat(entry.action()).isEqualTo(IdentityAuditActions.LOGIN_SUCCEEDED);
        assertThat(entry.entityId()).isEqualTo(ID);
        assertThat(entry.actorAccountId()).isEqualTo(ID);
        assertThat(entry.actorEmail()).isEqualTo(EMAIL);
        assertThat(entry.before()).as("bộ đếm không đổi thì không ghi before").isNull();
        assertThat(entry.after()).isEqualTo(Map.of("sessionId", 99L));
    }

    @Test
    void successAfterFailuresResetsCounterAndAuditsBeforeAfter() {
        Account account = staff(AccountStatus.ACTIVE);
        ReflectionTestUtils.setField(account, "failedLoginCount", 3);
        ReflectionTestUtils.setField(account, "firstFailedLoginAt", NOW.minusSeconds(60));
        lockable(account);

        login(PASSWORD, true);

        assertThat(account.getFailedLoginCount()).isZero();
        assertThat(account.getFirstFailedLoginAt()).isNull();
        AuditEntry entry = recordedEntry();
        assertThat(entry.before()).isEqualTo(new LoginCounterSnapshot(3, null));
        @SuppressWarnings("unchecked")
        Map<String, Object> after = (Map<String, Object>) entry.after();
        assertThat(after).containsEntry("sessionId", 99L).containsEntry("failedLoginCount", 0)
                .containsEntry("lockedUntil", null);
    }

    @Test
    void customerSuccessReadsLinkDecisionPending() {
        Account account = customer(AccountStatus.ACTIVE);
        lockable(account);
        when(customers.findCustomerIdByAccountId(ID)).thenReturn(Optional.of(500L));
        when(customers.findContact(500L)).thenReturn(Optional.of(
                new CustomerContact(500L, "Khách", "0901234567", EMAIL, ID, true)));

        LoginResponse response = login(PASSWORD, true);

        assertThat(response.linkDecisionPending()).isTrue();
    }

    @Test
    void customerWithoutProfileIsDataErrorNot401() {
        Account account = customer(AccountStatus.ACTIVE);
        lockable(account);
        when(customers.findCustomerIdByAccountId(ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> login(PASSWORD, true)).isInstanceOf(IllegalStateException.class);
        verify(audit, never()).record(any());
    }

    // ---------------------------------------------------------------- hash đổi giữa bước đọc và bước khóa

    @Test
    void hashChangedUnderLockIsRecheckedAgainstNewHash() {
        Account account = staff(AccountStatus.ACTIVE);
        lockable(account);
        String newHash = encoder.encode("matkhaumoi9");
        ReflectionTestUtils.setField(account, "passwordHash", newHash);

        // Đúng với hash cũ (TX-1) nhưng mật khẩu đã đổi: phải tính là sai.
        assertThatThrownBy(() -> service.login(new AccountCredential(ID, hash), EMAIL, PASSWORD, true, IP, UA))
                .isInstanceOf(InvalidCredentialsException.class);
        assertThat(account.getFailedLoginCount()).isEqualTo(1);

        // Sai với hash cũ nhưng đúng mật khẩu mới.
        LoginResponse response = service.login(new AccountCredential(ID, hash), EMAIL, "matkhaumoi9", false, IP, UA);
        assertThat(response.accessToken()).isEqualTo("jwt-token");
    }

    // ---------------------------------------------------------------- tiện ích

    @Test
    void unlockTimeIsRoundedUpToMinuteInVietnamTime() {
        assertThat(LoginAttemptService.formatUnlockTime(Instant.parse("2026-10-08T02:15:00Z")))
                .isEqualTo("09:15 08/10/2026");
        assertThat(LoginAttemptService.formatUnlockTime(Instant.parse("2026-10-08T02:15:00.001Z")))
                .isEqualTo("09:16 08/10/2026");
        assertThat(LoginAttemptService.formatUnlockTime(Instant.parse("2026-10-08T16:59:30Z")))
                .as("qua nửa đêm giờ VN").isEqualTo("00:00 09/10/2026");
    }

    @Test
    void passwordLongerThan72BytesNeverFits() {
        assertThat(LoginAttemptService.fitsBcrypt("a".repeat(72))).isTrue();
        assertThat(LoginAttemptService.fitsBcrypt("a".repeat(73))).isFalse();
        assertThat(LoginAttemptService.fitsBcrypt("ă".repeat(36))).as("ă = 2 byte UTF-8").isTrue();
        assertThat(LoginAttemptService.fitsBcrypt("ă".repeat(37))).isFalse();
    }

    private LoginResponse login(String password, boolean matched) {
        return service.login(new AccountCredential(ID, hash), EMAIL, password, matched, IP, UA);
    }

    private void lockable(Account account) {
        ReflectionTestUtils.setField(account, "passwordHash", hash);
        when(accounts.findByIdForUpdate(ID)).thenReturn(Optional.of(account));
    }

    private void assertNothingWritten(Account account) {
        verify(audit, never()).record(any());
        verify(audit, never()).recordIndependently(any());
        verifyNoInteractions(sessions, notifications);
        assertThat(account.getLockedUntil() == null || account.getLockedUntil().isAfter(NOW)).isTrue();
    }

    private AuditEntry recordedEntry() {
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).record(captor.capture());
        return captor.getValue();
    }

    private static Account staff(AccountStatus status) {
        Account account = Account.registerCustomer(EMAIL, "x", null);
        ReflectionTestUtils.setField(account, "id", ID);
        ReflectionTestUtils.setField(account, "role", Role.RECEPTIONIST);
        ReflectionTestUtils.setField(account, "phone", "0900000000");
        ReflectionTestUtils.setField(account, "status", status);
        return account;
    }

    private static Account customer(AccountStatus status) {
        Account account = Account.registerCustomer(EMAIL, "x", null);
        ReflectionTestUtils.setField(account, "id", ID);
        ReflectionTestUtils.setField(account, "status", status);
        return account;
    }
}
