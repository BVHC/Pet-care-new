package com.petcare.module.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.care.api.NotificationApi;
import com.petcare.module.care.api.NotificationApi.Channel;
import com.petcare.module.care.api.NotificationApi.NotificationRequest;
import com.petcare.module.customer.api.CustomerApi;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi.CustomerContact;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.NotificationTemplateCode;
import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.identity.dto.GoogleLoginRequest;
import com.petcare.module.identity.dto.LoginResponse;
import com.petcare.module.identity.entity.Account;
import com.petcare.module.identity.entity.AccountStatus;
import com.petcare.module.identity.exception.InvalidCredentialsException;
import com.petcare.module.identity.exception.LoginRejectedException;
import com.petcare.module.identity.fsm.AccountTransitionHandler;
import com.petcare.module.identity.google.GoogleIdentity;
import com.petcare.module.identity.mapper.LoginMapper;
import com.petcare.module.identity.repository.AccountCredential;
import com.petcare.module.identity.repository.AccountRepository;
import com.petcare.module.identity.repository.OtpTokenRepository;
import com.petcare.module.identity.service.LoginAttemptService.LoginCounterSnapshot;
import com.petcare.module.identity.service.SessionService.OpenedSession;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ErrorCode;

import jakarta.persistence.EntityManager;

/**
 * UC03 dưới khóa (docs/adr/0019): thứ tự tồn tại → khóa tạm → mật khẩu → trạng thái (BR-TK-08, 09, 10, 11), bộ đếm
 * và email cảnh báo (BR-TK-09, ST01), audit từng nhánh (BR-QT-15), nhánh 400 ném trước mọi lệnh ghi. Đăng nhập Google
 * (docs/adr/0152, spec 2026-11-24 §6.3): khóa theo {@code sub} rồi email, bảng quyết định, thay tài khoản {@code PENDING}.
 */
class LoginAttemptServiceTest {

    private static final long ID = 7L;
    private static final String EMAIL = "nv@petcare.test";
    private static final String PASSWORD = "matkhau123";
    private static final String IP = "10.0.0.1";
    private static final String UA = "JUnit";
    /** 09:00 giờ Việt Nam. */
    private static final Instant NOW = Instant.parse("2026-10-08T02:00:00Z");

    private static final String UNUSABLE = "$2a$04$unusable...";
    private static final String SUB = "g-1";
    private static final String GMAIL = "lan@gmail.com";
    private static final String YAHOO = "lan@yahoo.test";
    /** Id mà {@code accounts.save} (IDENTITY) gán cho tài khoản Google mới. */
    private static final long NEW_ID = 42L;
    private static final GoogleLoginRequest NO_CONSENT = new GoogleLoginRequest("cred", null, null, null);

    private final AccountRepository accounts = mock(AccountRepository.class);
    private final SessionService sessions = mock(SessionService.class);
    private final CustomerQueryApi customers = mock(CustomerQueryApi.class);
    private final NotificationApi notifications = mock(NotificationApi.class);
    private final SystemConfigApi configs = mock(SystemConfigApi.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final CustomerApi customerProfiles = mock(CustomerApi.class);
    private final OtpTokenRepository otpTokens = mock(OtpTokenRepository.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final LoginAttemptService service = new LoginAttemptService(accounts, sessions, customers, notifications,
            configs, audit, encoder, Mappers.getMapper(LoginMapper.class), Clock.fixed(NOW, TimeConfig.BUSINESS_ZONE),
            customerProfiles, otpTokens, new AccountTransitionHandler(), entityManager);

    private String hash;

    @BeforeEach
    void setUp() {
        hash = encoder.encode(PASSWORD);
        when(configs.getInt(ConfigKey.LOGIN_MAX_FAILED_ATTEMPTS)).thenReturn(5);
        when(configs.getInt(ConfigKey.LOGIN_FAILED_WINDOW_MINUTES)).thenReturn(15);
        when(configs.getInt(ConfigKey.LOGIN_LOCK_MINUTES)).thenReturn(15);
        when(sessions.open(anyLong(), any(), any()))
                .thenReturn(new OpenedSession(99L, "jwt-token", NOW.plus(Duration.ofHours(12)),
                        NOW.plus(Duration.ofMinutes(10)), "refresh-token"));
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
        assertThat(response.accessTokenExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
        assertThat(response.refreshToken()).isEqualTo("refresh-token");
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
                .containsEntry("lockedUntil", null)
                .as("đăng nhập mật khẩu không ghi method").doesNotContainKey("method");
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

    // ---------------------------------------------------------------- đăng nhập Google: tạo mới

    @Test
    void googleNewCustomerCreatesActiveAccountProfileAndSession() {
        creatable();
        when(configs.getInt(ConfigKey.ACCOUNT_PENDING_TTL_HOURS)).thenReturn(24);

        LoginResponse response = google(gmail(), consent("0901234567"));

        assertThat(response.accessToken()).isEqualTo("jwt-token");
        Account saved = savedAccount();
        assertThat(saved.getEmail()).isEqualTo(GMAIL);
        assertThat(saved.getRole()).isEqualTo(Role.CUSTOMER);
        assertThat(saved.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(saved.getPendingExpiresAt()).isNull();
        assertThat(saved.getGoogleSub()).isEqualTo(SUB);
        assertThat(saved.getPasswordHash()).isEqualTo(UNUSABLE);

        InOrder order = inOrder(accounts, customerProfiles, sessions);
        order.verify(accounts).findByGoogleSubForUpdate(SUB);
        order.verify(accounts).findByEmailForUpdate(GMAIL);
        order.verify(accounts).save(saved);
        order.verify(customerProfiles).createOnlineProfile(NEW_ID, "Nguyễn Lan", "0901234567");
        order.verify(customerProfiles).flagLinkDecisionIfPhoneMatches(NEW_ID);
        order.verify(sessions).open(NEW_ID, IP, UA);

        AuditEntry entry = recordedEntry();
        assertThat(entry.action()).isEqualTo(IdentityAuditActions.LOGIN_SUCCEEDED);
        assertThat(entry.entityId()).isEqualTo(NEW_ID);
        assertThat(afterData(entry)).containsEntry("method", IdentityAuditActions.METHOD_GOOGLE)
                .containsEntry("method", "GOOGLE").containsEntry("accountCreated", true);
        verifyNoInteractions(otpTokens, entityManager, notifications);
    }

    @Test
    void googleNewCustomerWithoutPhoneSkipsLinkFlag() {
        creatable();

        google(gmail(), consent(null));

        verify(customerProfiles).createOnlineProfile(NEW_ID, "Nguyễn Lan", null);
        verify(customerProfiles, never()).flagLinkDecisionIfPhoneMatches(anyLong());
    }

    @Test
    void googleNewCustomerWithoutConsentThrowsBrTk02() {
        for (GoogleLoginRequest request : List.of(new GoogleLoginRequest("cred", null, true, null),
                new GoogleLoginRequest("cred", true, false, null), NO_CONSENT)) {
            assertThatThrownBy(() -> google(gmail(), request))
                    .as(request.toString())
                    .isInstanceOf(BusinessRuleViolationException.class)
                    .isNotInstanceOf(LoginRejectedException.class)
                    .hasMessage("Bạn cần xác nhận đủ 18 tuổi và đồng ý điều khoản sử dụng (BR-TK-02)")
                    .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId())
                            .isEqualTo("BR-TK-02"));
        }
        verify(accounts, never()).save(any());
        verifyNoInteractions(sessions, customerProfiles, otpTokens, entityManager, audit);
    }

    @Test
    void googleNonAuthoritativeNewEmailCreatesAccount() {
        creatable();

        google(new GoogleIdentity(SUB, "a@yahoo.test", "An", false), consent(null));

        Account saved = savedAccount();
        assertThat(saved.getEmail()).isEqualTo("a@yahoo.test");
        assertThat(saved.getGoogleSub()).isEqualTo(SUB);
        verify(customerProfiles).createOnlineProfile(NEW_ID, "An", null);
    }

    @Test
    void googleFullNameFallsBackAndTruncatesByCodePoint() {
        creatable();

        google(new GoogleIdentity(SUB, "lan.nguyen@gmail.com", null, true), consent(null));
        verify(customerProfiles).createOnlineProfile(NEW_ID, "lan.nguyen", null);

        String grinning = "😀";   // một code point, hai char (cặp surrogate)
        google(new GoogleIdentity("g-2", "dai@gmail.com", "a".repeat(99) + grinning + "bbb", true), consent(null));
        ArgumentCaptor<String> fullName = ArgumentCaptor.forClass(String.class);
        verify(customerProfiles, times(2)).createOnlineProfile(eq(NEW_ID), fullName.capture(), any());
        String stored = fullName.getAllValues().get(1);
        assertThat(stored.codePointCount(0, stored.length())).isEqualTo(100);
        assertThat(stored).endsWith(grinning);
    }

    // ---------------------------------------------------------------- đăng nhập Google: tài khoản có sẵn

    @Test
    void googleBoundSubLogsInWithoutEmailLookup() {
        Account account = customerAccount(GMAIL, AccountStatus.ACTIVE, SUB);
        when(accounts.findByGoogleSubForUpdate(SUB)).thenReturn(Optional.of(account));
        profile(ID);

        // Email của token đổi: sub thắng, email tài khoản giữ nguyên, không khóa thêm dòng theo email.
        google(new GoogleIdentity(SUB, "doi-email@gmail.com", "Lan", true), NO_CONSENT);

        verify(accounts, never()).findByEmailForUpdate(any());
        verify(accounts, never()).save(any());
        verify(sessions, times(1)).open(ID, IP, UA);
        assertThat(account.getEmail()).isEqualTo(GMAIL);
        assertThat(afterData(recordedEntry())).containsEntry("method", "GOOGLE").doesNotContainKey("accountCreated");
        verifyNoInteractions(customerProfiles, otpTokens, entityManager);
    }

    @Test
    void googleLinksExistingActiveGmailCustomer() {
        Account account = customerAccount(GMAIL, AccountStatus.ACTIVE, null);
        when(accounts.findByEmailForUpdate(GMAIL)).thenReturn(Optional.of(account));
        profile(ID);

        google(gmail(), NO_CONSENT);

        assertThat(account.getGoogleSub()).isEqualTo(SUB);
        assertThat(account.getPasswordHash()).as("mật khẩu cũ vẫn dùng song song").isEqualTo("x");
        verify(customerProfiles, never()).createOnlineProfile(anyLong(), any(), any());
        verify(accounts, never()).save(any());
        verify(sessions).open(ID, IP, UA);
        assertThat(afterData(recordedEntry())).containsEntry("method", "GOOGLE").doesNotContainKey("accountCreated");
    }

    @Test
    void googleNonAuthoritativeExistingAccountRejected() {
        for (AccountStatus status : List.of(AccountStatus.ACTIVE, AccountStatus.PENDING)) {
            Account account = customerAccount(YAHOO, status, null);
            when(accounts.findByEmailForUpdate(YAHOO)).thenReturn(Optional.of(account));

            assertThatThrownBy(() -> google(new GoogleIdentity(SUB, YAHOO, "Lan", false), consent(null)))
                    .as(status.name())
                    .isInstanceOf(LoginRejectedException.class)
                    .hasMessage("Email này đã có tài khoản. Vui lòng đăng nhập bằng email và mật khẩu (BR-TK-01)")
                    .satisfies(ex -> assertThat(((LoginRejectedException) ex).auditEntry().reason())
                            .isEqualTo(IdentityAuditActions.REASON_GOOGLE_EMAIL_NOT_AUTHORITATIVE));
            assertThat(account.getGoogleSub()).isNull();
            assertThat(account.getStatus()).isEqualTo(status);
        }
        verify(accounts, never()).deletePendingById(anyLong());
        verify(accounts, never()).save(any());
        verify(audit, never()).record(any());
        verifyNoInteractions(sessions, customerProfiles, otpTokens, entityManager);
    }

    @Test
    void googleSubMismatchRecordsAuditAndThrows401() {
        Account account = customerAccount(GMAIL, AccountStatus.ACTIVE, "other");
        when(accounts.findByEmailForUpdate(GMAIL)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> google(gmail(), consent(null)))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage(InvalidCredentialsException.GOOGLE_MESSAGE);

        AuditEntry entry = recordedEntry();
        assertThat(entry.action()).isEqualTo(IdentityAuditActions.LOGIN_FAILED);
        assertThat(entry.reason()).isEqualTo(IdentityAuditActions.REASON_GOOGLE_SUB_MISMATCH);
        assertThat(entry.entityId()).isEqualTo(ID);
        assertThat(account.getGoogleSub()).isEqualTo("other");
        verifyNoInteractions(sessions, customerProfiles, otpTokens, entityManager);
    }

    @Test
    void googleEmailAccountAlreadyBoundToSameSubLogsIn() {
        // Request song song cùng token: tìm theo sub không thấy (request kia chưa commit), khóa theo email thì thấy
        // dòng vừa được gắn đúng sub này.
        Account account = customerAccount(GMAIL, AccountStatus.ACTIVE, SUB);
        when(accounts.findByEmailForUpdate(GMAIL)).thenReturn(Optional.of(account));
        profile(ID);

        LoginResponse response = google(gmail(), NO_CONSENT);

        assertThat(response.accessToken()).isEqualTo("jwt-token");
        assertThat(account.getGoogleSub()).isEqualTo(SUB);
        AuditEntry entry = recordedEntry();
        assertThat(entry.action()).isEqualTo(IdentityAuditActions.LOGIN_SUCCEEDED);
        assertThat(afterData(entry)).containsEntry("method", "GOOGLE");
    }

    @Test
    void googleStaffEmailRejectedBrTk17() {
        Account account = staff(AccountStatus.ACTIVE);
        ReflectionTestUtils.setField(account, "role", Role.VET);
        when(accounts.findByEmailForUpdate(EMAIL)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> google(new GoogleIdentity(SUB, EMAIL, "BS", true), consent(null)))
                .isInstanceOf(LoginRejectedException.class)
                .hasMessage("Tài khoản nhân viên đăng nhập bằng email và mật khẩu (BR-TK-17)")
                .satisfies(ex -> assertThat(((LoginRejectedException) ex).auditEntry().reason())
                        .isEqualTo(IdentityAuditActions.REASON_GOOGLE_STAFF));

        assertThat(account.getGoogleSub()).isNull();
        assertNothingWritten(account);
        verifyNoInteractions(customerProfiles, otpTokens, entityManager);
    }

    @Test
    void googleTemporarilyLockedCustomerRejectedBrTk09() {
        Account account = customerAccount(GMAIL, AccountStatus.ACTIVE, null);
        Instant until = NOW.plus(Duration.ofMinutes(5));
        ReflectionTestUtils.setField(account, "lockedUntil", until);
        when(accounts.findByEmailForUpdate(GMAIL)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> google(gmail(), NO_CONSENT))
                .isInstanceOf(LoginRejectedException.class)
                .hasMessage(LoginAttemptService.MSG_TEMPORARILY_LOCKED
                        .formatted(LoginAttemptService.formatUnlockTime(until)) + " (BR-TK-09)")
                .satisfies(ex -> assertThat(((LoginRejectedException) ex).auditEntry().reason())
                        .isEqualTo(IdentityAuditActions.REASON_TEMPORARILY_LOCKED));

        assertThat(account.getGoogleSub()).as("chưa gắn khi bị chặn").isNull();
        assertNothingWritten(account);
    }

    @Test
    void googleLockedCustomerRejectedBrTk11() {
        Account bound = customerAccount(GMAIL, AccountStatus.ACTIVE, SUB);
        ReflectionTestUtils.setField(bound, "locked", true);
        when(accounts.findByGoogleSubForUpdate(SUB)).thenReturn(Optional.of(bound));
        Account unbound = customerAccount("khac@gmail.com", AccountStatus.ACTIVE, null);
        ReflectionTestUtils.setField(unbound, "locked", true);
        when(accounts.findByEmailForUpdate("khac@gmail.com")).thenReturn(Optional.of(unbound));

        for (GoogleIdentity identity : List.of(gmail(), new GoogleIdentity("g-2", "khac@gmail.com", "Khác", true))) {
            assertThatThrownBy(() -> google(identity, NO_CONSENT))
                    .as(identity.sub())
                    .isInstanceOf(LoginRejectedException.class)
                    .hasMessage("Tài khoản đã bị khóa. Vui lòng liên hệ Pet Care để được hỗ trợ (BR-TK-11)")
                    .satisfies(ex -> assertThat(((LoginRejectedException) ex).auditEntry().reason())
                            .isEqualTo(IdentityAuditActions.REASON_LOCKED));
        }

        assertThat(unbound.getGoogleSub()).isNull();
        assertNothingWritten(unbound);
    }

    // ---------------------------------------------------------------- đăng nhập Google: thay tài khoản PENDING

    @Test
    void googlePendingAuthoritativeIsDeletedThenRecreated() {
        Account pending = customerAccount(GMAIL, AccountStatus.PENDING, null);
        when(accounts.findByEmailForUpdate(GMAIL)).thenReturn(Optional.of(pending));
        when(accounts.deletePendingById(ID)).thenReturn(1);
        creatable();

        google(gmail(), consent(null));

        InOrder order = inOrder(otpTokens, customerProfiles, accounts, entityManager);
        order.verify(otpTokens).deleteByAccountId(ID);
        order.verify(customerProfiles).deleteOnlineProfileOfUnverifiedAccount(ID);
        order.verify(accounts).deletePendingById(ID);
        order.verify(entityManager).detach(pending);
        order.verify(accounts).save(argThat(saved -> saved != pending));
        Account saved = savedAccount();
        assertThat(saved.getPasswordHash()).as("không giữ mật khẩu của người đăng ký trước").isEqualTo(UNUSABLE);
        assertThat(saved.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(saved.getGoogleSub()).isEqualTo(SUB);
        assertThat(pending.getStatus()).as("không kích hoạt PENDING tại chỗ").isEqualTo(AccountStatus.PENDING);
        assertThat(pending.getGoogleSub()).isNull();
        verify(customerProfiles).createOnlineProfile(NEW_ID, "Nguyễn Lan", null);
        assertThat(afterData(recordedEntry())).containsEntry("accountCreated", true);
        verifyNoInteractions(notifications);
    }

    @Test
    void googlePendingWithoutConsentDeletesNothing() {
        Account pending = customerAccount(GMAIL, AccountStatus.PENDING, null);
        when(accounts.findByEmailForUpdate(GMAIL)).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> google(gmail(), NO_CONSENT))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageEndingWith("(BR-TK-02)");

        verify(accounts, never()).deletePendingById(anyLong());
        verify(accounts, never()).save(any());
        verifyNoInteractions(otpTokens, customerProfiles, entityManager, sessions, audit);
    }

    @Test
    void googlePendingTemporarilyLockedIsStillReplaced() {
        Account pending = customerAccount(GMAIL, AccountStatus.PENDING, null);
        ReflectionTestUtils.setField(pending, "lockedUntil", NOW.plus(Duration.ofMinutes(5)));
        when(accounts.findByEmailForUpdate(GMAIL)).thenReturn(Optional.of(pending));
        when(accounts.deletePendingById(ID)).thenReturn(1);
        creatable();

        LoginResponse response = google(gmail(), consent(null));

        assertThat(response.accessToken()).isEqualTo("jwt-token");
        verify(accounts).deletePendingById(ID);
        assertThat(savedAccount().getLockedUntil()).isNull();
    }

    @Test
    void googlePendingDeleteMismatchThrows() {
        Account pending = customerAccount(GMAIL, AccountStatus.PENDING, null);
        when(accounts.findByEmailForUpdate(GMAIL)).thenReturn(Optional.of(pending));
        when(accounts.deletePendingById(ID)).thenReturn(0);

        assertThatThrownBy(() -> google(gmail(), consent(null))).isInstanceOf(IllegalStateException.class);

        verify(accounts, never()).save(any());
        verifyNoInteractions(sessions, entityManager);
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

    private LoginResponse google(GoogleIdentity identity, GoogleLoginRequest request) {
        return service.loginWithGoogle(identity, request, UNUSABLE, IP, UA);
    }

    private static GoogleIdentity gmail() {
        return new GoogleIdentity(SUB, GMAIL, "Nguyễn Lan", true);
    }

    private static GoogleLoginRequest consent(String phone) {
        return new GoogleLoginRequest("cred", true, true, phone);
    }

    /** {@code save} gán id như IDENTITY; {@code succeed} đọc cờ BR-TK-19 của hồ sơ khách mới. */
    private void creatable() {
        when(accounts.save(any(Account.class))).thenAnswer(invocation -> {
            Account saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", NEW_ID);
            return saved;
        });
        profile(NEW_ID);
    }

    private void profile(long accountId) {
        when(customers.findCustomerIdByAccountId(accountId)).thenReturn(Optional.of(500L));
        when(customers.findContact(500L)).thenReturn(Optional.of(
                new CustomerContact(500L, "Lan", null, GMAIL, accountId, false)));
    }

    private Account savedAccount() {
        ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
        verify(accounts).save(captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> afterData(AuditEntry entry) {
        return (Map<String, Object>) entry.after();
    }

    private static Account customerAccount(String email, AccountStatus status, String googleSub) {
        Account account = Account.registerCustomer(email, "x",
                status == AccountStatus.PENDING ? NOW.plus(Duration.ofHours(24)) : null);
        ReflectionTestUtils.setField(account, "id", ID);
        ReflectionTestUtils.setField(account, "status", status);
        ReflectionTestUtils.setField(account, "googleSub", googleSub);
        return account;
    }
}
