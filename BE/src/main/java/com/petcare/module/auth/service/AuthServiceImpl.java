package com.petcare.module.auth.service;

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
import com.petcare.platform.enums.SecurityScope;
import com.petcare.platform.enums.UserRole;
import com.petcare.platform.exception.AccountLockedException;
import com.petcare.platform.exception.AccountNotActiveException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ConcurrencyConflictException;
import com.petcare.platform.exception.InvalidCredentialsException;
import com.petcare.platform.exception.InvalidRefreshTokenException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.outbox.OutboxEvent;
import com.petcare.platform.outbox.OutboxEventRepository;
import com.petcare.platform.security.JwtTokenProvider;
import com.petcare.platform.security.UserPrincipal;
import com.petcare.platform.security.token.IssuedTokenPair;
import com.petcare.platform.security.token.TokenIssuanceFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

/**
 * Module 01 (Auth & OTP) — RegisterAccount + VerifyOTP + ResendOTP.
 * Guard RULE-ID validate ở Service, ngay trước ghi dữ liệu, trong cùng
 * transaction (docs/convention/backend/06-validation.md).
 */
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final int OTP_TTL_SECONDS = 300;
    private static final int OTP_LOCKOUT_MINUTES = 15;
    private static final int OTP_MAX_ATTEMPTS = 5;
    private static final int OTP_RESEND_COOLDOWN_SECONDS = 60;
    private static final int OTP_MAX_RESEND_PER_HOUR = 5;
    private static final int PASSWORD_MIN_LENGTH = 8;
    private static final int LOGIN_MAX_FAILED_ATTEMPTS = 5;
    private static final int LOGIN_LOCKOUT_MINUTES = 15;

    private final AccountRepository accountRepository;
    private final OtpRepository otpRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final AccountTransitionHandler accountTransitionHandler;
    private final UserProvisioningService userProvisioningService;
    private final NotificationService notificationService;
    private final PasswordEncoder passwordEncoder;
    private final TokenIssuanceFacade tokenIssuanceFacade;
    private final JwtTokenProvider jwtTokenProvider;
    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    @Transactional
    public RegistrationOutcome registerAccount(RegisterRequest request) {
        if (request.password().length() < PASSWORD_MIN_LENGTH) {
            throw new BusinessRuleViolationException("RULE-01-09",
                    "Mật khẩu phải có ít nhất " + PASSWORD_MIN_LENGTH + " ký tự");
        }
        if (accountRepository.existsByEmail(request.email())) {
            throw new BusinessRuleViolationException("RULE-01-10", "Email đã được sử dụng");
        }

        Account account = new Account(request.email(), request.phone(), passwordEncoder.encode(request.password()));
        try {
            account = accountRepository.save(account);
        } catch (DataIntegrityViolationException ex) {
            // §5.1 Concurrency: 2 request register cùng email gần như đồng thời — pre-check
            // ở trên có thể đều thấy "chưa tồn tại" (race). UNIQUE constraint ở DB là guard
            // thật; request thua ở đây nhận lỗi nghiệp vụ sạch thay vì 500.
            throw new BusinessRuleViolationException("RULE-01-10", "Email đã được sử dụng");
        }

        User user = userProvisioningService.createCustomerProfile(account.getId(), request.name());

        String otpCode = generateOtpCode();
        Otp otp = new Otp(request.email(), otpCode, OtpPurpose.REGISTRATION,
                LocalDateTime.now().plusSeconds(OTP_TTL_SECONDS));
        otpRepository.save(otp);

        recordOutboxEvent(account, "AccountRegistered", otp.getId());

        var notificationTaskId = notificationService.enqueue(user.getId(), NotificationChannel.EMAIL,
                "OTP", buildOtpEmailContent(otpCode));

        return new RegistrationOutcome(account, notificationTaskId);
    }

    /**
     * noRollbackFor: các guard-throw (sai OTP/hết hạn/lock phiên) xảy ra SAU
     * khi attempt_count/is_used/locked_until đã save — phải commit để lần thử
     * kế tiếp thấy đúng bộ đếm (RULE-01-02/05). Không rollback = cố ý.
     */
    @Override
    @Transactional(noRollbackFor = BusinessRuleViolationException.class)
    public Account verifyOtp(VerifyOtpRequest request) {
        Otp otp = otpRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(request.email(), OtpPurpose.REGISTRATION)
                .orElseThrow(() -> new ResourceNotFoundException("Otp", request.email()));

        if (otp.isCurrentlyLocked()) {
            throw new BusinessRuleViolationException("RULE-01-05",
                    "Phiên xác thực OTP đang bị khoá tạm thời, vui lòng thử lại sau");
        }
        if (otp.isUsed() || otp.isExpired()) {
            throw new BusinessRuleViolationException("RULE-01-02", "OTP đã hết hạn hoặc không còn hiệu lực");
        }

        if (!otp.getOtpCode().equals(request.otpCode())) {
            otp.setAttemptCount(otp.getAttemptCount() + 1);
            if (otp.getAttemptCount() >= OTP_MAX_ATTEMPTS) {
                otp.setUsed(true);
                otp.setLockedUntil(LocalDateTime.now().plusMinutes(OTP_LOCKOUT_MINUTES));
                otpRepository.save(otp);
                throw new BusinessRuleViolationException("RULE-01-05",
                        "Nhập sai OTP quá " + OTP_MAX_ATTEMPTS + " lần, phiên xác thực bị khoá "
                                + OTP_LOCKOUT_MINUTES + " phút");
            }
            otpRepository.save(otp);
            throw new BusinessRuleViolationException("RULE-01-02", "Mã OTP không đúng");
        }

        otp.setUsed(true);
        otpRepository.save(otp);

        Account account = accountRepository.findByEmail(request.email())
                .orElseThrow(() -> new ResourceNotFoundException("Account", request.email()));

        accountTransitionHandler.validateTransition(account.getStatus(), AccountStatus.ACTIVE);
        account.setStatus(AccountStatus.ACTIVE);
        try {
            account = accountRepository.save(account);
        } catch (ObjectOptimisticLockingFailureException ex) {
            // §5.2 defense-in-depth — trong thực tế khó xảy ra vì pessimistic lock trên Otp
            // (findTopByEmailAndPurpose...) đã serialize verify-otp theo (email, purpose).
            throw new ConcurrencyConflictException("Account", account.getId());
        }

        recordOutboxEvent(account, "AccountActivated", null);
        return account;
    }

    @Override
    @Transactional
    public RegistrationOutcome resendOtp(ResendOtpRequest request) {
        Account account = accountRepository.findByEmail(request.email())
                .orElseThrow(() -> new ResourceNotFoundException("Account", request.email()));
        if (account.getStatus() != AccountStatus.PENDING_VERIFICATION) {
            throw new BusinessRuleViolationException("RULE-01-03",
                    "Tài khoản đã xác thực, không cần gửi lại OTP");
        }

        Optional<Otp> currentOtp = otpRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(
                request.email(), OtpPurpose.REGISTRATION);

        if (currentOtp.isPresent()) {
            Otp otp = currentOtp.get();
            if (otp.isCurrentlyLocked()) {
                throw new BusinessRuleViolationException("RULE-01-05",
                        "Phiên xác thực OTP đang bị khoá tạm thời, vui lòng thử lại sau");
            }
            long secondsSinceLast = ChronoUnit.SECONDS.between(otp.getCreatedAt(), LocalDateTime.now());
            if (secondsSinceLast < OTP_RESEND_COOLDOWN_SECONDS) {
                throw new BusinessRuleViolationException("RULE-01-04",
                        "Vui lòng chờ trước khi gửi lại OTP");
            }
        }

        long resentInLastHour = otpRepository.countByEmailAndPurposeAndCreatedAtAfter(
                request.email(), OtpPurpose.REGISTRATION, LocalDateTime.now().minusHours(1));
        if (resentInLastHour >= OTP_MAX_RESEND_PER_HOUR) {
            throw new BusinessRuleViolationException("RULE-01-05",
                    "Đã vượt giới hạn gửi lại OTP trong 1 giờ");
        }

        currentOtp.filter(otp -> !otp.isUsed()).ifPresent(otp -> {
            otp.setUsed(true);
            otpRepository.save(otp);
        });

        String otpCode = generateOtpCode();
        Otp newOtp = new Otp(request.email(), otpCode, OtpPurpose.REGISTRATION,
                LocalDateTime.now().plusSeconds(OTP_TTL_SECONDS));
        otpRepository.save(newOtp);

        User user = userProvisioningService.findByAccountId(account.getId());
        var notificationTaskId = notificationService.enqueue(user.getId(), NotificationChannel.EMAIL,
                "OTP", buildOtpEmailContent(otpCode));

        return new RegistrationOutcome(account, notificationTaskId);
    }

    /**
     * noRollbackFor: failedLoginAttempts + LOCKED save trước khi ném
     * InvalidCredentials/AccountLocked phải commit để đủ 5 lần thì khóa
     * (RULE-01-07). Các RuntimeException bất ngờ khác vẫn rollback.
     */
    @Override
    @Transactional(noRollbackFor = BusinessRuleViolationException.class)
    public LoginResponse login(LoginRequest request, String userAgent, String ipAddress) {
        Account account = accountRepository.findByEmail(request.email())
                .orElseThrow(InvalidCredentialsException::new);

        maybeAutoUnlock(account);

        if (account.getStatus() == AccountStatus.LOCKED) {
            throw new AccountLockedException(account.getLockedUntil());
        }
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new AccountNotActiveException(account.getStatus());
        }

        if (!passwordEncoder.matches(request.password(), account.getPasswordHash())) {
            registerFailedLoginAttempt(account);
        }

        account.setFailedLoginAttempts(0);
        try {
            account = accountRepository.save(account);
        } catch (ObjectOptimisticLockingFailureException ex) {
            throw new ConcurrencyConflictException("Account", account.getId());
        }

        User user = userProvisioningService.findByAccountId(account.getId());
        UserPrincipal principal = buildPrincipal(account, user);
        IssuedTokenPair tokens = tokenIssuanceFacade.issueTokens(principal, userAgent, ipAddress);

        return new LoginResponse(tokens.accessToken(), tokens.refreshToken(), tokens.tokenType(), tokens.expiresIn());
    }

    @Override
    @Transactional
    public LogoutResponse logout(String rawAccessToken, LogoutRequest request) {
        String refreshToken = request != null ? request.refreshToken() : null;
        tokenIssuanceFacade.logout(rawAccessToken, refreshToken);
        return new LogoutResponse(true);
    }

    @Override
    @Transactional
    public RefreshTokenResponse refresh(RefreshTokenRequest request, String userAgent, String ipAddress) {
        if (!jwtTokenProvider.validateToken(request.refreshToken())
                || !JwtTokenProvider.TOKEN_TYPE_REFRESH.equals(jwtTokenProvider.getTokenType(request.refreshToken()))) {
            throw new InvalidRefreshTokenException();
        }

        UUID userId = jwtTokenProvider.parseToken(request.refreshToken()).getUserId();
        User user = userProvisioningService.findById(userId);
        Account account = accountRepository.findById(user.getAccountId())
                .orElseThrow(() -> new ResourceNotFoundException("Account", user.getAccountId()));

        if (account.getStatus() == AccountStatus.LOCKED) {
            throw new AccountLockedException(account.getLockedUntil());
        }
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new AccountNotActiveException(account.getStatus());
        }

        UserPrincipal principal = buildPrincipal(account, user);
        IssuedTokenPair tokens;
        try {
            tokens = tokenIssuanceFacade.refreshTokens(request.refreshToken(), principal, userAgent, ipAddress);
        } catch (IllegalArgumentException ex) {
            throw new InvalidRefreshTokenException();
        }

        return new RefreshTokenResponse(tokens.accessToken(), tokens.refreshToken(), tokens.tokenType(), tokens.expiresIn());
    }

    /**
     * RULE-01-07 (AutoUnlockAccount) — chỉ áp dụng cho khóa AUTO_FAILED_LOGIN,
     * KHÔNG áp dụng cho ADMIN_LOCK (không có locked_until/không tự mở khóa).
     * Gọi ở đầu login/refresh vì đó là hành động kế tiếp tự nhiên nhất để
     * kiểm tra điều kiện mở khóa theo thời gian.
     */
    private void maybeAutoUnlock(Account account) {
        if (account.getStatus() != AccountStatus.LOCKED
                || account.getLockReason() != LockReason.AUTO_FAILED_LOGIN
                || account.getLockedUntil() == null
                || LocalDateTime.now().isBefore(account.getLockedUntil())) {
            return;
        }
        accountTransitionHandler.validateTransition(AccountStatus.LOCKED, AccountStatus.ACTIVE);
        account.setStatus(AccountStatus.ACTIVE);
        account.setLockReason(null);
        account.setLockedUntil(null);
        account.setFailedLoginAttempts(0);
        accountRepository.save(account);
        recordOutboxEvent(account, "AccountUnlocked", null);
    }

    /** RULE-01-07 (AutoLockAccount) — 5 lần sai mật khẩu liên tiếp -> LOCKED 15 phút. */
    private void registerFailedLoginAttempt(Account account) {
        account.setFailedLoginAttempts(account.getFailedLoginAttempts() + 1);
        if (account.getFailedLoginAttempts() < LOGIN_MAX_FAILED_ATTEMPTS) {
            accountRepository.save(account);
            throw new InvalidCredentialsException();
        }

        accountTransitionHandler.validateTransition(AccountStatus.ACTIVE, AccountStatus.LOCKED);
        account.setStatus(AccountStatus.LOCKED);
        account.setLockReason(LockReason.AUTO_FAILED_LOGIN);
        account.setLockedUntil(LocalDateTime.now().plusMinutes(LOGIN_LOCKOUT_MINUTES));
        accountRepository.save(account);
        recordOutboxEvent(account, "AccountLocked", null);
        throw new AccountLockedException(account.getLockedUntil());
    }

    private UserPrincipal buildPrincipal(Account account, User user) {
        return UserPrincipal.builder()
                .userId(user.getId())
                .accountId(account.getId())
                .phone(account.getPhone())
                .name(user.getFullName())
                .role(user.getRole())
                .scope(deriveScope(user.getRole()))
                .accountStatus(account.getStatus())
                .organizationId(user.getOrganizationId())
                .storeId(user.getStoreId())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> findUserIdByActiveAccountEmail(String email) {
        return accountRepository.findByEmail(email)
                .filter(account -> account.getStatus() == AccountStatus.ACTIVE)
                .map(account -> userProvisioningService.findByAccountId(account.getId()).getId());
    }

    /** Bảng RBAC 5-tier (docs/INDEX.md) — role -> scope mặc định. */
    private SecurityScope deriveScope(UserRole role) {
        return switch (role) {
            case SUPER_ADMIN -> SecurityScope.PLATFORM;
            case ORGANIZATION_ADMIN -> SecurityScope.ORGANIZATION;
            case STORE_MANAGER, FINANCE_STAFF, INVENTORY_STAFF, RECEPTIONIST, VETERINARIAN, GROOMER ->
                    SecurityScope.STORE;
            case CUSTOMER -> SecurityScope.CUSTOMER;
        };
    }

    private String generateOtpCode() {
        int code = secureRandom.nextInt(1_000_000);
        return String.format("%06d", code);
    }

    private String buildOtpEmailContent(String otpCode) {
        return "Mã xác thực Pet Care của bạn là " + otpCode + ", hết hạn sau "
                + (OTP_TTL_SECONDS / 60) + " phút. Không chia sẻ mã này cho bất kỳ ai.";
    }

    private void recordOutboxEvent(Account account, String eventType, Object extraIdForLog) {
        OutboxEvent event = new OutboxEvent();
        event.setAggregateType("Account");
        event.setAggregateId(account.getId().toString());
        event.setEventType(eventType);
        event.setPayload(buildEventPayloadJson(account, extraIdForLog));
        outboxEventRepository.save(event);
    }

    private String buildEventPayloadJson(Account account, Object otpId) {
        String otpIdField = otpId == null ? "" : ",\"otpId\":\"" + otpId + "\"";
        return "{\"accountId\":\"" + account.getId() + "\",\"email\":\"" + account.getEmail() + "\"" + otpIdField + "}";
    }
}
