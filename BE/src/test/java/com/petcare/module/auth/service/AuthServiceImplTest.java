package com.petcare.module.auth.service;

import com.petcare.module.auth.dto.CreateCustomerRequest;
import com.petcare.module.auth.dto.CreateStaffRequest;
import com.petcare.module.auth.dto.LoginRequest;
import com.petcare.module.auth.dto.LoginResponse;
import com.petcare.module.auth.dto.LogoutRequest;
import com.petcare.module.auth.dto.LogoutResponse;
import com.petcare.module.auth.dto.RefreshTokenRequest;
import com.petcare.module.auth.dto.RefreshTokenResponse;
import com.petcare.module.auth.dto.RegisterRequest;
import com.petcare.module.auth.dto.ResendOtpRequest;
import com.petcare.module.auth.dto.VerifyOtpRequest;
import com.petcare.module.auth.entity.Account;
import com.petcare.module.auth.entity.Otp;
import com.petcare.module.auth.fsm.AccountTransitionHandler;
import com.petcare.module.auth.repository.AccountRepository;
import com.petcare.module.auth.repository.OtpRepository;
import com.petcare.module.iam.entity.User;
import com.petcare.module.iam.service.UserProvisioningService;
import com.petcare.module.notification.service.NotificationService;
import com.petcare.platform.enums.AccountStatus;
import com.petcare.platform.enums.LockReason;
import com.petcare.platform.enums.NotificationChannel;
import com.petcare.platform.enums.OtpPurpose;
import com.petcare.platform.enums.UserRole;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.AccountLockedException;
import com.petcare.platform.exception.AccountNotActiveException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.InvalidCredentialsException;
import com.petcare.platform.exception.InvalidRefreshTokenException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.JwtTokenProvider;
import com.petcare.platform.security.UserPrincipal;
import com.petcare.platform.security.token.IssuedTokenPair;
import com.petcare.platform.security.token.TokenIssuanceFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock
    private AccountRepository accountRepository;
    @Mock
    private OtpRepository otpRepository;
    @Mock
    private AccountEventRecorder accountEventRecorder;
    @Mock
    private UserProvisioningService userProvisioningService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private TokenIssuanceFacade tokenIssuanceFacade;
    @Mock
    private JwtTokenProvider jwtTokenProvider;

    private final AccountTransitionHandler accountTransitionHandler = new AccountTransitionHandler();

    private AuthServiceImpl authService;

    private static final String EMAIL = "customer@example.com";

    @BeforeEach
    void setUp() {
        authService = new AuthServiceImpl(accountRepository, otpRepository,
                accountTransitionHandler, userProvisioningService, notificationService, passwordEncoder,
                tokenIssuanceFacade, jwtTokenProvider, accountEventRecorder);
    }

    private static Account activeAccount() {
        Account account = new Account(EMAIL, null, "hashed");
        account.setId(UUID.randomUUID());
        account.setStatus(AccountStatus.ACTIVE);
        return account;
    }

    private static User customerUser(UUID accountId) {
        User user = new User(accountId, "Nguyen Van A");
        user.setId(UUID.randomUUID());
        return user;
    }

    // ---- registerAccount ----

    @Test
    void registerAccount_rejectsShortPassword_RULE_01_09() {
        RegisterRequest request = new RegisterRequest(EMAIL, null, "short", "Nguyen Van A");

        assertThatThrownBy(() -> authService.registerAccount(request))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo("RULE-01-09"));

        verify(accountRepository, never()).save(any());
    }

    @Test
    void registerAccount_rejectsDuplicateEmail_preCheck_RULE_01_10() {
        RegisterRequest request = new RegisterRequest(EMAIL, null, "password123", "Nguyen Van A");
        when(accountRepository.existsByEmail(EMAIL)).thenReturn(true);

        assertThatThrownBy(() -> authService.registerAccount(request))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo("RULE-01-10"));

        verify(accountRepository, never()).save(any());
    }

    @Test
    void registerAccount_rejectsDuplicateEmail_raceOnUniqueConstraint_RULE_01_10() {
        // §5.1 Concurrency: 2 request cùng email — pre-check pass (race), DB unique constraint chặn ở save().
        RegisterRequest request = new RegisterRequest(EMAIL, null, "password123", "Nguyen Van A");
        when(accountRepository.existsByEmail(EMAIL)).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");
        when(accountRepository.save(any(Account.class))).thenThrow(new DataIntegrityViolationException("uq_accounts_email"));

        assertThatThrownBy(() -> authService.registerAccount(request))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo("RULE-01-10"));
    }

    @Test
    void registerAccount_happyPath_createsUserAndOtp_andEnqueuesNotification() {
        RegisterRequest request = new RegisterRequest(EMAIL, "0912345678", "password123", "Nguyen Van A");
        when(accountRepository.existsByEmail(EMAIL)).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("hashed");
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> {
            Account a = inv.getArgument(0);
            a.setId(UUID.randomUUID());
            return a;
        });
        User user = new User(UUID.randomUUID(), "Nguyen Van A");
        user.setId(UUID.randomUUID());
        when(userProvisioningService.createCustomerProfile(any(), eq("Nguyen Van A"))).thenReturn(user);
        UUID taskId = UUID.randomUUID();
        when(notificationService.enqueue(eq(user.getId()), eq(NotificationChannel.EMAIL), eq("OTP"), anyString()))
                .thenReturn(taskId);

        RegistrationOutcome outcome = authService.registerAccount(request);

        assertThat(outcome.account().getStatus()).isEqualTo(AccountStatus.PENDING_VERIFICATION);
        assertThat(outcome.notificationTaskId()).isEqualTo(taskId);
        verify(otpRepository).save(any(Otp.class));
        verify(accountEventRecorder).record(any(), eq("AccountRegistered"), any());
    }

    // ---- verifyOtp ----

    @Test
    void verifyOtp_notFound_throwsResourceNotFound() {
        when(otpRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(EMAIL, OtpPurpose.REGISTRATION))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.verifyOtp(new VerifyOtpRequest(EMAIL, "123456")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void verifyOtp_currentlyLocked_RULE_01_05() {
        Otp otp = new Otp(EMAIL, "111111", OtpPurpose.REGISTRATION, LocalDateTime.now().plusMinutes(5));
        otp.setLockedUntil(LocalDateTime.now().plusMinutes(10));
        when(otpRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(EMAIL, OtpPurpose.REGISTRATION))
                .thenReturn(Optional.of(otp));

        assertThatThrownBy(() -> authService.verifyOtp(new VerifyOtpRequest(EMAIL, "111111")))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo("RULE-01-05"));
    }

    @Test
    void verifyOtp_expired_RULE_01_02() {
        Otp otp = new Otp(EMAIL, "111111", OtpPurpose.REGISTRATION, LocalDateTime.now().minusSeconds(1));
        when(otpRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(EMAIL, OtpPurpose.REGISTRATION))
                .thenReturn(Optional.of(otp));

        assertThatThrownBy(() -> authService.verifyOtp(new VerifyOtpRequest(EMAIL, "111111")))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo("RULE-01-02"));
    }

    @Test
    void verifyOtp_wrongCode_belowMaxAttempts_incrementsCount_RULE_01_02() {
        Otp otp = new Otp(EMAIL, "111111", OtpPurpose.REGISTRATION, LocalDateTime.now().plusMinutes(5));
        otp.setAttemptCount(2);
        when(otpRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(EMAIL, OtpPurpose.REGISTRATION))
                .thenReturn(Optional.of(otp));

        assertThatThrownBy(() -> authService.verifyOtp(new VerifyOtpRequest(EMAIL, "999999")))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo("RULE-01-02"));

        assertThat(otp.getAttemptCount()).isEqualTo(3);
        assertThat(otp.isCurrentlyLocked()).isFalse();
    }

    @Test
    void verifyOtp_wrongCode_reachesMaxAttempts_locksSession_RULE_01_05() {
        Otp otp = new Otp(EMAIL, "111111", OtpPurpose.REGISTRATION, LocalDateTime.now().plusMinutes(5));
        otp.setAttemptCount(4);
        when(otpRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(EMAIL, OtpPurpose.REGISTRATION))
                .thenReturn(Optional.of(otp));

        assertThatThrownBy(() -> authService.verifyOtp(new VerifyOtpRequest(EMAIL, "999999")))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo("RULE-01-05"));

        assertThat(otp.getAttemptCount()).isEqualTo(5);
        assertThat(otp.isUsed()).isTrue();
        assertThat(otp.isCurrentlyLocked()).isTrue();
    }

    @Test
    void verifyOtp_correctCode_activatesAccount() {
        Otp otp = new Otp(EMAIL, "111111", OtpPurpose.REGISTRATION, LocalDateTime.now().plusMinutes(5));
        when(otpRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(EMAIL, OtpPurpose.REGISTRATION))
                .thenReturn(Optional.of(otp));
        Account account = new Account(EMAIL, null, "hashed");
        account.setId(UUID.randomUUID());
        when(accountRepository.findByEmail(EMAIL)).thenReturn(Optional.of(account));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        Account result = authService.verifyOtp(new VerifyOtpRequest(EMAIL, "111111"));

        assertThat(result.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(otp.isUsed()).isTrue();
        verify(accountEventRecorder).record(any(), eq("AccountActivated"));
    }

    // ---- resendOtp ----

    @Test
    void resendOtp_accountAlreadyActive_RULE_01_03() {
        Account account = new Account(EMAIL, null, "hashed");
        account.setStatus(AccountStatus.ACTIVE);
        when(accountRepository.findByEmail(EMAIL)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> authService.resendOtp(new ResendOtpRequest(EMAIL)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo("RULE-01-03"));
    }

    @Test
    void resendOtp_cooldownNotElapsed_RULE_01_04() {
        Account account = new Account(EMAIL, null, "hashed");
        when(accountRepository.findByEmail(EMAIL)).thenReturn(Optional.of(account));
        Otp otp = new Otp(EMAIL, "111111", OtpPurpose.REGISTRATION, LocalDateTime.now().plusMinutes(5));
        otp.setCreatedAt(LocalDateTime.now().minusSeconds(10));
        when(otpRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(EMAIL, OtpPurpose.REGISTRATION))
                .thenReturn(Optional.of(otp));

        assertThatThrownBy(() -> authService.resendOtp(new ResendOtpRequest(EMAIL)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo("RULE-01-04"));
    }

    @Test
    void resendOtp_rateLimitExceeded_RULE_01_05() {
        Account account = new Account(EMAIL, null, "hashed");
        when(accountRepository.findByEmail(EMAIL)).thenReturn(Optional.of(account));
        Otp otp = new Otp(EMAIL, "111111", OtpPurpose.REGISTRATION, LocalDateTime.now().plusMinutes(5));
        otp.setCreatedAt(LocalDateTime.now().minusMinutes(5));
        when(otpRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(EMAIL, OtpPurpose.REGISTRATION))
                .thenReturn(Optional.of(otp));
        when(otpRepository.countByEmailAndPurposeAndCreatedAtAfter(eq(EMAIL), eq(OtpPurpose.REGISTRATION), any()))
                .thenReturn(5L);

        assertThatThrownBy(() -> authService.resendOtp(new ResendOtpRequest(EMAIL)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo("RULE-01-05"));
    }

    @Test
    void resendOtp_happyPath_invalidatesOldAndCreatesNew() {
        Account account = new Account(EMAIL, null, "hashed");
        account.setId(UUID.randomUUID());
        when(accountRepository.findByEmail(EMAIL)).thenReturn(Optional.of(account));
        Otp oldOtp = new Otp(EMAIL, "111111", OtpPurpose.REGISTRATION, LocalDateTime.now().plusMinutes(5));
        oldOtp.setCreatedAt(LocalDateTime.now().minusMinutes(5));
        when(otpRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(EMAIL, OtpPurpose.REGISTRATION))
                .thenReturn(Optional.of(oldOtp));
        when(otpRepository.countByEmailAndPurposeAndCreatedAtAfter(eq(EMAIL), eq(OtpPurpose.REGISTRATION), any()))
                .thenReturn(1L);
        User user = new User(account.getId(), "Nguyen Van A");
        user.setId(UUID.randomUUID());
        when(userProvisioningService.findByAccountId(account.getId())).thenReturn(user);
        UUID taskId = UUID.randomUUID();
        when(notificationService.enqueue(eq(user.getId()), eq(NotificationChannel.EMAIL), eq("OTP"), anyString()))
                .thenReturn(taskId);

        RegistrationOutcome outcome = authService.resendOtp(new ResendOtpRequest(EMAIL));

        assertThat(oldOtp.isUsed()).isTrue();
        assertThat(outcome.notificationTaskId()).isEqualTo(taskId);
        verify(otpRepository).save(argThat(o -> o != oldOtp));
    }

    // ---- login ----

    @Test
    void login_unknownEmail_throwsInvalidCredentials_RULE_01_01() {
        when(accountRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, "password123"), "ua", "127.0.0.1"))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(tokenIssuanceFacade, never()).issueTokens(any(), any(), any());
    }

    @Test
    void login_pendingVerification_throwsAccountNotActive_RULE_01_01() {
        Account account = activeAccount();
        account.setStatus(AccountStatus.PENDING_VERIFICATION);
        when(accountRepository.findByEmail(EMAIL)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, "password123"), "ua", "127.0.0.1"))
                .isInstanceOf(AccountNotActiveException.class);
    }

    @Test
    void login_deactivated_throwsAccountNotActive_RULE_01_01() {
        Account account = activeAccount();
        account.setStatus(AccountStatus.DEACTIVATED);
        when(accountRepository.findByEmail(EMAIL)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, "password123"), "ua", "127.0.0.1"))
                .isInstanceOf(AccountNotActiveException.class);
    }

    @Test
    void login_lockedStillWithinWindow_throwsAccountLocked_RULE_01_07() {
        Account account = activeAccount();
        account.setStatus(AccountStatus.LOCKED);
        account.setLockReason(LockReason.AUTO_FAILED_LOGIN);
        account.setLockedUntil(LocalDateTime.now().plusMinutes(10));
        when(accountRepository.findByEmail(EMAIL)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, "password123"), "ua", "127.0.0.1"))
                .isInstanceOf(AccountLockedException.class);

        assertThat(account.getStatus()).isEqualTo(AccountStatus.LOCKED);
    }

    @Test
    void login_adminLocked_expiredTimestamp_doesNotAutoUnlock_RULE_01_07() {
        // Ghi chú RULE-01-07: AutoUnlock CHỈ áp dụng lock_reason=AUTO_FAILED_LOGIN, không áp dụng ADMIN_LOCK.
        Account account = activeAccount();
        account.setStatus(AccountStatus.LOCKED);
        account.setLockReason(LockReason.ADMIN_LOCK);
        account.setLockedUntil(LocalDateTime.now().minusMinutes(1));
        when(accountRepository.findByEmail(EMAIL)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, "password123"), "ua", "127.0.0.1"))
                .isInstanceOf(AccountLockedException.class);

        assertThat(account.getStatus()).isEqualTo(AccountStatus.LOCKED);
        assertThat(account.getLockReason()).isEqualTo(LockReason.ADMIN_LOCK);
    }

    @Test
    void login_lockedWindowExpired_autoUnlocksThenRejectsWrongPassword_RULE_01_07() {
        Account account = activeAccount();
        account.setStatus(AccountStatus.LOCKED);
        account.setLockReason(LockReason.AUTO_FAILED_LOGIN);
        account.setLockedUntil(LocalDateTime.now().minusMinutes(1));
        account.setFailedLoginAttempts(5);
        when(accountRepository.findByEmail(EMAIL)).thenReturn(Optional.of(account));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));
        when(passwordEncoder.matches("wrong", account.getPasswordHash())).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, "wrong"), "ua", "127.0.0.1"))
                .isInstanceOf(InvalidCredentialsException.class);

        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.getLockReason()).isNull();
        assertThat(account.getLockedUntil()).isNull();
        assertThat(account.getFailedLoginAttempts()).isEqualTo(1);
        verify(accountEventRecorder).record(any(), eq("AccountUnlocked"));
    }

    @Test
    void login_wrongPassword_belowThreshold_incrementsCounter_RULE_01_01() {
        Account account = activeAccount();
        account.setFailedLoginAttempts(2);
        when(accountRepository.findByEmail(EMAIL)).thenReturn(Optional.of(account));
        when(passwordEncoder.matches("wrong", account.getPasswordHash())).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, "wrong"), "ua", "127.0.0.1"))
                .isInstanceOf(InvalidCredentialsException.class);

        assertThat(account.getFailedLoginAttempts()).isEqualTo(3);
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        verify(accountRepository).save(account);
    }

    @Test
    void login_wrongPassword_reachesThreshold_locksAccount_RULE_01_07() {
        Account account = activeAccount();
        account.setFailedLoginAttempts(4);
        when(accountRepository.findByEmail(EMAIL)).thenReturn(Optional.of(account));
        when(passwordEncoder.matches("wrong", account.getPasswordHash())).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, "wrong"), "ua", "127.0.0.1"))
                .isInstanceOf(AccountLockedException.class);

        assertThat(account.getFailedLoginAttempts()).isEqualTo(5);
        assertThat(account.getStatus()).isEqualTo(AccountStatus.LOCKED);
        assertThat(account.getLockReason()).isEqualTo(LockReason.AUTO_FAILED_LOGIN);
        assertThat(account.getLockedUntil()).isAfter(LocalDateTime.now());
        verify(accountEventRecorder).record(any(), eq("AccountLocked"), any());
    }

    @Test
    void login_correctPassword_resetsCounterAndIssuesTokens() {
        Account account = activeAccount();
        account.setFailedLoginAttempts(3);
        when(accountRepository.findByEmail(EMAIL)).thenReturn(Optional.of(account));
        when(passwordEncoder.matches("password123", account.getPasswordHash())).thenReturn(true);
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));
        User user = customerUser(account.getId());
        when(userProvisioningService.findByAccountId(account.getId())).thenReturn(user);
        IssuedTokenPair tokens = new IssuedTokenPair("access-token", "refresh-token", "Bearer", 900L);
        when(tokenIssuanceFacade.issueTokens(any(UserPrincipal.class), eq("ua"), eq("127.0.0.1"))).thenReturn(tokens);

        LoginResponse response = authService.login(new LoginRequest(EMAIL, "password123"), "ua", "127.0.0.1");

        assertThat(account.getFailedLoginAttempts()).isEqualTo(0);
        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.refreshToken()).isEqualTo("refresh-token");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(900L);
        verify(tokenIssuanceFacade).issueTokens(argThat(p -> p.getUserId().equals(user.getId())
                && p.getAccountId().equals(account.getId())), eq("ua"), eq("127.0.0.1"));
    }

    // ---- createStaff ----

    private static UserPrincipal admin(UserRole role, UUID organizationId, UUID storeId) {
        return UserPrincipal.builder()
                .userId(UUID.randomUUID())
                .role(role)
                .organizationId(organizationId)
                .storeId(storeId)
                .build();
    }

    @Test
    void createStaff_rejectsShortPassword_RULE_01_09() {
        var actor = admin(UserRole.SUPER_ADMIN, null, null);
        var request = new CreateStaffRequest(
                "staff@example.com", null, "short", "Nguyen Van B", UserRole.RECEPTIONIST,
                UUID.randomUUID(), UUID.randomUUID());

        assertThatThrownBy(() -> authService.createStaff(request, actor))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo("RULE-01-09"));

        verify(accountRepository, never()).save(any());
    }

    @Test
    void createStaff_outOfScopeAndShortPassword_deniedByScopeNotPasswordRule() {
        // Kiểm tra quyền phải chạy TRƯỚC validation nghiệp vụ khác — actor ngoài scope
        // không được nhận nhầm BUSINESS_RULE_VIOLATION (password ngắn) che mất lỗi quyền thật.
        UUID orgId = UUID.randomUUID();
        var actor = admin(UserRole.STORE_MANAGER, orgId, UUID.randomUUID());
        var request = new CreateStaffRequest(
                "staff@example.com", null, "short", "Nguyen Van B", UserRole.STORE_MANAGER, orgId, actor.getStoreId());

        assertThatThrownBy(() -> authService.createStaff(request, actor))
                .isInstanceOf(AccessDeniedScopeException.class);

        verify(accountRepository, never()).existsByEmail(any());
        verify(accountRepository, never()).save(any());
    }

    @Test
    void createStaff_orgAdminEscalatesToOrgAdmin_deniedByRoleScopeGuard_RULE_02_03() {
        UUID orgId = UUID.randomUUID();
        var actor = admin(UserRole.ORGANIZATION_ADMIN, orgId, null);
        var request = new CreateStaffRequest(
                "staff@example.com", null, "password123", "Nguyen Van B", UserRole.ORGANIZATION_ADMIN, orgId, null);

        assertThatThrownBy(() -> authService.createStaff(request, actor))
                .isInstanceOf(AccessDeniedScopeException.class);

        verify(accountRepository, never()).save(any());
    }

    @Test
    void createStaff_duplicateEmail_RULE_01_10() {
        var actor = admin(UserRole.SUPER_ADMIN, null, null);
        var request = new CreateStaffRequest(
                EMAIL, null, "password123", "Nguyen Van B", UserRole.RECEPTIONIST, UUID.randomUUID(), UUID.randomUUID());
        when(accountRepository.existsByEmail(EMAIL)).thenReturn(true);

        assertThatThrownBy(() -> authService.createStaff(request, actor))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo("RULE-01-10"));
    }

    @Test
    void createStaff_happyPath_createsActiveAccountWithTempPassword() {
        UUID orgId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        var actor = admin(UserRole.SUPER_ADMIN, null, null);
        var request = new CreateStaffRequest(
                "staff@example.com", "0912345678", "password123", "Nguyen Van B", UserRole.RECEPTIONIST, orgId, storeId);
        when(accountRepository.existsByEmail("staff@example.com")).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("hashed");
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> {
            Account a = inv.getArgument(0);
            a.setId(UUID.randomUUID());
            return a;
        });
        User staffUser = new User(UUID.randomUUID(), "Nguyen Van B");
        staffUser.setId(UUID.randomUUID());
        when(userProvisioningService.createStaffProfile(any(), eq("Nguyen Van B"), eq(UserRole.RECEPTIONIST),
                eq(orgId), eq(storeId))).thenReturn(staffUser);

        var response = authService.createStaff(request, actor);

        assertThat(response.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(response.mustChangePassword()).isTrue();
        assertThat(response.userId()).isEqualTo(staffUser.getId());
        verify(accountEventRecorder).record(any(), eq("AccountActivated"));
        verify(accountEventRecorder).record(any(), eq("PermissionAssigned"), any());
    }

    // ---- createCustomer ----

    @Test
    void createCustomer_rejectsShortPassword_RULE_01_09() {
        var request = new CreateCustomerRequest("customer2@example.com", null, "short", "Nguyen Thi C");

        assertThatThrownBy(() -> authService.createCustomer(request))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo("RULE-01-09"));

        verify(accountRepository, never()).save(any());
    }

    @Test
    void createCustomer_duplicateEmail_RULE_01_10() {
        var request = new CreateCustomerRequest(EMAIL, null, "password123", "Nguyen Thi C");
        when(accountRepository.existsByEmail(EMAIL)).thenReturn(true);

        assertThatThrownBy(() -> authService.createCustomer(request))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo("RULE-01-10"));

        verify(accountRepository, never()).save(any());
    }

    @Test
    void createCustomer_happyPath_createsActiveAccountWithTempPassword() {
        var request = new CreateCustomerRequest("customer2@example.com", "0912345678", "password123", "Nguyen Thi C");
        when(accountRepository.existsByEmail("customer2@example.com")).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("hashed");
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> {
            Account a = inv.getArgument(0);
            a.setId(UUID.randomUUID());
            return a;
        });
        User customer = new User(UUID.randomUUID(), "Nguyen Thi C");
        customer.setId(UUID.randomUUID());
        when(userProvisioningService.createCustomerProfile(any(), eq("Nguyen Thi C"))).thenReturn(customer);

        var response = authService.createCustomer(request);

        assertThat(response.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(response.mustChangePassword()).isTrue();
        assertThat(response.userId()).isEqualTo(customer.getId());
        verify(accountEventRecorder).record(any(), eq("AccountActivated"));
    }

    // ---- logout ----

    @Test
    void logout_delegatesToTokenIssuanceFacade_RULE_01_06() {
        LogoutResponse response = authService.logout("access-raw", new LogoutRequest("refresh-raw"));

        assertThat(response.revoked()).isTrue();
        verify(tokenIssuanceFacade).logout("access-raw", "refresh-raw");
    }

    @Test
    void logout_nullBody_stillRevokesAccessToken_RULE_01_06() {
        LogoutResponse response = authService.logout("access-raw", null);

        assertThat(response.revoked()).isTrue();
        verify(tokenIssuanceFacade).logout("access-raw", null);
    }

    // ---- refresh ----

    @Test
    void refresh_invalidJwt_throwsInvalidRefreshToken_RULE_01_06() {
        when(jwtTokenProvider.validateToken("bad-token")).thenReturn(false);

        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest("bad-token"), "ua", "127.0.0.1"))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void refresh_wrongTokenType_throwsInvalidRefreshToken_RULE_01_06() {
        when(jwtTokenProvider.validateToken("access-token")).thenReturn(true);
        when(jwtTokenProvider.getTokenType("access-token")).thenReturn(JwtTokenProvider.TOKEN_TYPE_ACCESS);

        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest("access-token"), "ua", "127.0.0.1"))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void refresh_accountLocked_throwsAccountLocked_RULE_01_07() {
        Account account = activeAccount();
        account.setStatus(AccountStatus.LOCKED);
        account.setLockReason(LockReason.ADMIN_LOCK);
        User user = customerUser(account.getId());
        stubValidRefreshToken("refresh-token", user.getId());
        when(userProvisioningService.findById(user.getId())).thenReturn(user);
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest("refresh-token"), "ua", "127.0.0.1"))
                .isInstanceOf(AccountLockedException.class);
    }

    @Test
    void refresh_accountDeactivated_throwsAccountNotActive_RULE_01_01() {
        Account account = activeAccount();
        account.setStatus(AccountStatus.DEACTIVATED);
        User user = customerUser(account.getId());
        stubValidRefreshToken("refresh-token", user.getId());
        when(userProvisioningService.findById(user.getId())).thenReturn(user);
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest("refresh-token"), "ua", "127.0.0.1"))
                .isInstanceOf(AccountNotActiveException.class);
    }

    @Test
    void refresh_facadeRejectsReuse_throwsInvalidRefreshToken_RULE_01_06() {
        Account account = activeAccount();
        User user = customerUser(account.getId());
        stubValidRefreshToken("refresh-token", user.getId());
        when(userProvisioningService.findById(user.getId())).thenReturn(user);
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        when(tokenIssuanceFacade.refreshTokens(eq("refresh-token"), any(UserPrincipal.class), eq("ua"), eq("127.0.0.1")))
                .thenThrow(new IllegalArgumentException("Refresh token not found or already revoked"));

        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest("refresh-token"), "ua", "127.0.0.1"))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void refresh_happyPath_rotatesTokens() {
        Account account = activeAccount();
        User user = customerUser(account.getId());
        stubValidRefreshToken("refresh-token", user.getId());
        when(userProvisioningService.findById(user.getId())).thenReturn(user);
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        IssuedTokenPair tokens = new IssuedTokenPair("new-access", "new-refresh", "Bearer", 900L);
        when(tokenIssuanceFacade.refreshTokens(eq("refresh-token"), any(UserPrincipal.class), eq("ua"), eq("127.0.0.1")))
                .thenReturn(tokens);

        RefreshTokenResponse response = authService.refresh(new RefreshTokenRequest("refresh-token"), "ua", "127.0.0.1");

        assertThat(response.accessToken()).isEqualTo("new-access");
        assertThat(response.refreshToken()).isEqualTo("new-refresh");
    }

    private void stubValidRefreshToken(String rawToken, UUID userId) {
        when(jwtTokenProvider.validateToken(rawToken)).thenReturn(true);
        when(jwtTokenProvider.getTokenType(rawToken)).thenReturn(JwtTokenProvider.TOKEN_TYPE_REFRESH);
        when(jwtTokenProvider.parseToken(rawToken)).thenReturn(UserPrincipal.builder().userId(userId).build());
    }
}
