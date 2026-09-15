package com.petcare.module.auth.service;

<<<<<<< HEAD
import com.petcare.module.auth.dto.ForgotPasswordRequest;
=======
import com.petcare.module.auth.dto.CreateCustomerRequest;
import com.petcare.module.auth.dto.CreateCustomerResponse;
import com.petcare.module.auth.dto.CreateStaffRequest;
import com.petcare.module.auth.dto.CreateStaffResponse;
>>>>>>> 8bfc5bd (feat: triển khai module iam)
import com.petcare.module.auth.dto.LoginRequest;
import com.petcare.module.auth.dto.LoginResponse;
import com.petcare.module.auth.dto.LogoutRequest;
import com.petcare.module.auth.dto.LogoutResponse;
import com.petcare.module.auth.dto.RefreshTokenRequest;
import com.petcare.module.auth.dto.RefreshTokenResponse;
import com.petcare.module.auth.dto.RegisterRequest;
import com.petcare.module.auth.dto.ResendOtpRequest;
import com.petcare.module.auth.dto.ResetPasswordRequest;
import com.petcare.module.auth.dto.VerifyOtpRequest;
import com.petcare.module.auth.entity.Account;
import com.petcare.module.auth.entity.Otp;
import com.petcare.module.auth.fsm.AccountTransitionHandler;
import com.petcare.module.auth.repository.AccountRepository;
import com.petcare.module.auth.repository.OtpRepository;
import com.petcare.module.iam.entity.User;
import com.petcare.module.iam.service.UserProvisioningService;
import com.petcare.module.notification.service.NotificationService;
import com.petcare.platform.audit.Auditable;
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
import com.petcare.platform.security.JwtTokenProvider;
import com.petcare.platform.security.RoleScopeGuard;
import com.petcare.platform.security.UserPrincipal;
import com.petcare.platform.security.token.IssuedTokenPair;
import com.petcare.platform.security.token.TokenIssuanceFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Module 01 (Auth & OTP) — RegisterAccount + VerifyOTP + ResendOTP +
 * Login/Logout/Refresh + ForgotPassword/ResetPassword.
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

    private static final String EMAIL_TAKEN = "Email đã được sử dụng";
    private static final String PHONE_TAKEN = "Số điện thoại đã được sử dụng";
    private static final int LOGIN_MAX_FAILED_ATTEMPTS = 5;
    private static final int LOGIN_LOCKOUT_MINUTES = 15;

    private final AccountRepository accountRepository;
    private final OtpRepository otpRepository;
    private final AccountTransitionHandler accountTransitionHandler;
    private final UserProvisioningService userProvisioningService;
    private final NotificationService notificationService;
    private final PasswordEncoder passwordEncoder;
    private final TokenIssuanceFacade tokenIssuanceFacade;
    private final JwtTokenProvider jwtTokenProvider;
    private final AccountEventRecorder accountEventRecorder;
    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    @Transactional
    public RegistrationOutcome registerAccount(RegisterRequest request) {
        if (request.password().length() < PASSWORD_MIN_LENGTH) {
            throw new BusinessRuleViolationException("RULE-01-09",
                    "Mật khẩu phải có ít nhất " + PASSWORD_MIN_LENGTH + " ký tự");
        }
        if (accountRepository.existsByEmail(request.email())) {
            throw new BusinessRuleViolationException("RULE-01-10", EMAIL_TAKEN);
        }
        // phone là optional nhưng UNIQUE ở DB — thiếu pre-check thì lỗi rơi xuống
        // constraint và trả 500 kèm nguyên câu SQL cho client.
        if (StringUtils.hasText(request.phone()) && accountRepository.existsByPhone(request.phone())) {
            throw new BusinessRuleViolationException("RULE-01-10", PHONE_TAKEN);
        }
        if (hasPhone(request.phone()) && accountRepository.existsByPhone(request.phone())) {
            throw new BusinessRuleViolationException("RULE-01-10", "Số điện thoại đã được sử dụng");
        }

        Account account = new Account(request.email(), request.phone(), passwordEncoder.encode(request.password()));
        try {
            // saveAndFlush (không phải save): save() chỉ đưa entity vào persistence
            // context, INSERT thật chạy lúc flush ở cuối transaction — tức là NGOÀI
            // try/catch này, nên DataIntegrityViolationException thoát ra thành 500.
            account = accountRepository.saveAndFlush(account);
        } catch (DataIntegrityViolationException ex) {
            // §5.1 Concurrency: 2 request register cùng email/phone gần như đồng thời —
            // pre-check ở trên có thể đều thấy "chưa tồn tại" (race). UNIQUE constraint ở
            // DB là guard thật; request thua ở đây nhận lỗi nghiệp vụ sạch thay vì 500.
<<<<<<< HEAD
            throw new BusinessRuleViolationException("RULE-01-10", constraintMessage(ex));
=======
            throw duplicateAccountException(ex);
>>>>>>> 8bfc5bd (feat: triển khai module iam)
        }

        User user = userProvisioningService.createCustomerProfile(account.getId(), request.name());

        String otpCode = generateOtpCode();
        Otp otp = new Otp(request.email(), otpCode, OtpPurpose.REGISTRATION,
                LocalDateTime.now().plusSeconds(OTP_TTL_SECONDS));
        otpRepository.save(otp);

        accountEventRecorder.record(account, "AccountRegistered", Map.of("otpId", String.valueOf(otp.getId())));

        var notificationTaskId = notificationService.enqueue(user.getId(), NotificationChannel.EMAIL,
                "OTP", buildOtpEmailContent(otpCode));

        return new RegistrationOutcome(account, notificationTaskId);
    }

    /**
<<<<<<< HEAD
     * noRollbackFor: các guard-throw (sai OTP/hết hạn/lock phiên) xảy ra SAU
     * khi attempt_count/is_used/locked_until đã save — phải commit để lần thử
     * kế tiếp thấy đúng bộ đếm (RULE-01-02/05). Không rollback = cố ý.
=======
     * noRollbackFor: sai OTP/khóa session (RULE-01-02/05) phải commit
     * attemptCount/lockedUntil dù method throw — mặc định Spring rollback
     * toàn bộ transaction trên RuntimeException sẽ xóa mất counter vừa
     * save() (bug thật, đã xảy ra). ConcurrencyConflictException/
     * ResourceNotFoundException KHÔNG extend BusinessRuleViolationException
     * nên vẫn rollback bình thường — bắt buộc, vì lúc đó Account activate
     * thất bại thì otp.used=true không được phép commit riêng lẻ. Khi sửa
     * method này, mọi save() mới đặt trước 1 throw BusinessRuleViolationException
     * (hoặc subclass) sẽ MẶC ĐỊNH được commit — cân nhắc kỹ trước khi thêm.
>>>>>>> 8bfc5bd (feat: triển khai module iam)
     */
    @Override
    @Transactional(noRollbackFor = BusinessRuleViolationException.class)
    public Account verifyOtp(VerifyOtpRequest request) {
        consumeOtp(request.email(), OtpPurpose.REGISTRATION, request.otpCode());

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

        accountEventRecorder.record(account, "AccountActivated");
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

        String otpCode = issueFreshOtp(request.email(), OtpPurpose.REGISTRATION);

        User user = userProvisioningService.findByAccountId(account.getId());
        var notificationTaskId = notificationService.enqueue(user.getId(), NotificationChannel.EMAIL,
                "OTP", buildOtpEmailContent(otpCode));

        return new RegistrationOutcome(account, notificationTaskId);
    }

    /**
<<<<<<< HEAD
     * noRollbackFor: failedLoginAttempts + LOCKED save trước khi ném
     * InvalidCredentials/AccountLocked phải commit để đủ 5 lần thì khóa
     * (RULE-01-07). Các RuntimeException bất ngờ khác vẫn rollback.
=======
     * noRollbackFor: sai mật khẩu/khóa account (RULE-01-01/07) phải commit
     * failedLoginAttempts/status=LOCKED dù method throw — cùng lý do như
     * verifyOtp ở trên. Không có noRollbackFor này, RULE-01-07 (khóa sau 5
     * lần sai) không bao giờ có hiệu lực thật vì counter luôn bị rollback về
     * giá trị cũ. ConcurrencyConflictException vẫn rollback bình thường (không
     * extend BusinessRuleViolationException) — đúng, vì reset counter bị
     * conflict thì không nên commit dở dang.
>>>>>>> 8bfc5bd (feat: triển khai module iam)
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

        // RULE-01-07 — cùng lý do như login(): nếu không gọi lại đây, tài khoản đã qua
        // locked_until (AUTO_FAILED_LOGIN) nhưng chỉ dùng refresh token (không login lại)
        // sẽ mãi bị từ chối dù lẽ ra phải tự ACTIVE.
        maybeAutoUnlock(account);

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
     * C8 ForgotPassword (docs/api/auth-v1.md C8, ASSUMPTION A8) — phát OTP mục đích PASSWORD_RESET.
     *
     * <p>Email lạ hoặc tài khoản chưa xác thực/đã vô hiệu hoá đều trả empty
     * thay vì ném lỗi: phản hồi phải giống hệt nhau ở mọi trường hợp, nếu
     * không kẻ tấn công dò được email nào có trong hệ thống.
     */
    @Override
    @Transactional
    public Optional<RegistrationOutcome> forgotPassword(ForgotPasswordRequest request) {
        Optional<Account> found = accountRepository.findByEmail(request.email());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Account account = found.get();
        // LOCKED vẫn cho đặt lại mật khẩu — đó chính là lối thoát khi bị khoá
        // do nhập sai nhiều lần (RULE-01-07).
        if (account.getStatus() != AccountStatus.ACTIVE && account.getStatus() != AccountStatus.LOCKED) {
            return Optional.empty();
        }

        String otpCode = issueFreshOtp(request.email(), OtpPurpose.PASSWORD_RESET);

        User user = userProvisioningService.findByAccountId(account.getId());
        var notificationTaskId = notificationService.enqueue(user.getId(), NotificationChannel.EMAIL,
                "OTP", buildResetEmailContent(otpCode));

        return Optional.of(new RegistrationOutcome(account, notificationTaskId));
    }

    /**
     * C9 ResetPassword (docs/api/auth-v1.md C9, ASSUMPTION A8) — đổi mật khẩu sau khi OTP PASSWORD_RESET hợp lệ.
     * Đặt lại mật khẩu cũng gỡ luôn khoá AUTO_FAILED_LOGIN.
     *
     * <p>noRollbackFor: consumeOtp ghi attempt_count/locked_until rồi mới ném
     * guard — phải commit thì đủ 5 lần sai mới khoá được phiên (RULE-01-05),
     * giống verifyOtp. Không rollback ở đây là cố ý.
     */
    @Override
    @Transactional(noRollbackFor = BusinessRuleViolationException.class)
    public void resetPassword(ResetPasswordRequest request) {
        if (request.newPassword().length() < PASSWORD_MIN_LENGTH) {
            throw new BusinessRuleViolationException("RULE-01-09",
                    "Mật khẩu phải có ít nhất " + PASSWORD_MIN_LENGTH + " ký tự");
        }

        consumeOtp(request.email(), OtpPurpose.PASSWORD_RESET, request.otpCode());

        Account account = accountRepository.findByEmail(request.email())
                .orElseThrow(() -> new ResourceNotFoundException("Account", request.email()));

        account.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        account.setMustChangePassword(false);
        account.setFailedLoginAttempts(0);
        if (account.getStatus() == AccountStatus.LOCKED && account.getLockReason() == LockReason.AUTO_FAILED_LOGIN) {
            accountTransitionHandler.validateTransition(AccountStatus.LOCKED, AccountStatus.ACTIVE);
            account.setStatus(AccountStatus.ACTIVE);
            account.setLockReason(null);
            account.setLockedUntil(null);
        }

        try {
            account = accountRepository.save(account);
        } catch (ObjectOptimisticLockingFailureException ex) {
            throw new ConcurrencyConflictException("Account", account.getId());
        }

        recordOutboxEvent(account, "PasswordReset", null);
    }

    /**
     * Kiểm tra + tiêu thụ OTP mới nhất của (email, purpose): khoá tạm 15 phút
     * sau {@value #OTP_MAX_ATTEMPTS} lần sai (RULE-01-05), hết hạn/đã dùng thì
     * từ chối (RULE-01-02). Dùng chung cho VerifyOTP và ResetPassword.
     */
    private void consumeOtp(String email, OtpPurpose purpose, String otpCode) {
        Otp otp = otpRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(email, purpose)
                .orElseThrow(() -> new ResourceNotFoundException("Otp", email));

        if (otp.isCurrentlyLocked()) {
            throw new BusinessRuleViolationException("RULE-01-05",
                    "Phiên xác thực OTP đang bị khoá tạm thời, vui lòng thử lại sau");
        }
        if (otp.isUsed() || otp.isExpired()) {
            throw new BusinessRuleViolationException("RULE-01-02", "OTP đã hết hạn hoặc không còn hiệu lực");
        }

        if (!otp.getOtpCode().equals(otpCode)) {
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
    }

    /**
     * Phát OTP mới cho (email, purpose) sau khi qua cooldown
     * {@value #OTP_RESEND_COOLDOWN_SECONDS}s (RULE-01-04) và hạn mức
     * {@value #OTP_MAX_RESEND_PER_HOUR} lần/giờ (RULE-01-05); OTP cũ còn hiệu
     * lực bị vô hiệu hoá. Dùng chung cho ResendOTP và ForgotPassword.
     */
    private String issueFreshOtp(String email, OtpPurpose purpose) {
        Optional<Otp> currentOtp = otpRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(email, purpose);

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

        long sentInLastHour = otpRepository.countByEmailAndPurposeAndCreatedAtAfter(
                email, purpose, LocalDateTime.now().minusHours(1));
        if (sentInLastHour >= OTP_MAX_RESEND_PER_HOUR) {
            throw new BusinessRuleViolationException("RULE-01-05",
                    "Đã vượt giới hạn gửi lại OTP trong 1 giờ");
        }

        currentOtp.filter(otp -> !otp.isUsed()).ifPresent(otp -> {
            otp.setUsed(true);
            otpRepository.save(otp);
        });

        String otpCode = generateOtpCode();
        otpRepository.save(new Otp(email, otpCode, purpose, LocalDateTime.now().plusSeconds(OTP_TTL_SECONDS)));
        return otpCode;
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
        accountEventRecorder.record(account, "AccountUnlocked");
    }

    /** RULE-01-07 (AutoLockAccount) — 5 lần sai mật khẩu liên tiếp -> LOCKED 15 phút. */
    private void registerFailedLoginAttempt(Account account) {
        account.setFailedLoginAttempts(account.getFailedLoginAttempts() + 1);
        if (account.getFailedLoginAttempts() < LOGIN_MAX_FAILED_ATTEMPTS) {
            saveWithConcurrencyCheck(account);
            throw new InvalidCredentialsException();
        }

        accountTransitionHandler.validateTransition(AccountStatus.ACTIVE, AccountStatus.LOCKED);
        account.setStatus(AccountStatus.LOCKED);
        account.setLockReason(LockReason.AUTO_FAILED_LOGIN);
        account.setLockedUntil(LocalDateTime.now().plusMinutes(LOGIN_LOCKOUT_MINUTES));
        saveWithConcurrencyCheck(account);
        accountEventRecorder.record(account, "AccountLocked", Map.of("lockReason", LockReason.AUTO_FAILED_LOGIN.name()));
        throw new AccountLockedException(account.getLockedUntil());
    }

    /**
     * 2 request login sai mật khẩu gần như đồng thời có thể cùng đọc
     * failedLoginAttempts cũ rồi cùng ghi đè — request thua cuộc phải nhận lỗi
     * nghiệp vụ sạch (409) thay vì rơi xuống handleGeneric (500).
     */
    private void saveWithConcurrencyCheck(Account account) {
        try {
            accountRepository.save(account);
        } catch (ObjectOptimisticLockingFailureException ex) {
            throw new ConcurrencyConflictException("Account", account.getId());
        }
    }

    private boolean hasPhone(String phone) {
        return phone != null && !phone.isBlank();
    }

    /**
     * RULE-01-10 — phân biệt email vs phone khi UNIQUE constraint ở DB chặn
     * (race condition mà pre-check existsByEmail/existsByPhone không bắt kịp),
     * thay vì luôn báo "Email đã được sử dụng" bất kể field nào thực sự trùng.
     */
    private BusinessRuleViolationException duplicateAccountException(DataIntegrityViolationException ex) {
        Throwable cause = ex.getMostSpecificCause();
        String detail = cause == null ? null : cause.getMessage();
        if (detail != null && detail.contains("uq_accounts_phone")) {
            return new BusinessRuleViolationException("RULE-01-10", "Số điện thoại đã được sử dụng");
        }
        return new BusinessRuleViolationException("RULE-01-10", "Email đã được sử dụng");
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

<<<<<<< HEAD
    /**
     * Phân biệt UNIQUE nào vừa vỡ để trả đúng thông điệp cho người dùng.
     * Chỉ đọc tên constraint — không bao giờ ghép nguyên message của DB vào
     * response, vì nó chứa cả câu SQL lẫn giá trị của tài khoản khác.
     */
    private String constraintMessage(DataIntegrityViolationException ex) {
        String raw = ex.getMostSpecificCause().getMessage();
        return raw != null && raw.contains("uq_accounts_phone") ? PHONE_TAKEN : EMAIL_TAKEN;
=======
    @Override
    @Transactional
    @Auditable(action = "CreateStaff")
    public CreateStaffResponse createStaff(CreateStaffRequest request, UserPrincipal actor) {
        // RULE-02-01/02/03 — kiểm tra thẩm quyền TRƯỚC mọi validation nghiệp vụ khác, để
        // actor ngoài scope luôn nhận đúng ACCESS_DENIED_SCOPE_MISMATCH thay vì lỡ nhận
        // nhầm BUSINESS_RULE_VIOLATION (vd password ngắn) rồi tưởng chỉ cần sửa password.
        RoleScopeGuard.assertCanAssignRole(actor, request.role(), request.organizationId(), request.storeId());

        // RULE-02-02 — /staff-accounts chỉ dành cho nhân sự (D-04); CUSTOMER đi qua
        // POST /customers (Receptionist) hoặc POST /auth/register (tự đăng ký), không đi
        // nhầm cửa này (bỏ qua OTP + mustChangePassword không phù hợp ngữ nghĩa Customer).
        if (request.role() == UserRole.CUSTOMER) {
            throw new BusinessRuleViolationException("RULE-02-02",
                    "Không thể tạo Customer qua /staff-accounts, dùng POST /customers");
        }

        if (request.password().length() < PASSWORD_MIN_LENGTH) {
            throw new BusinessRuleViolationException("RULE-01-09",
                    "Mật khẩu phải có ít nhất " + PASSWORD_MIN_LENGTH + " ký tự");
        }
        if (accountRepository.existsByEmail(request.email())) {
            throw new BusinessRuleViolationException("RULE-01-10", "Email đã được sử dụng");
        }
        if (hasPhone(request.phone()) && accountRepository.existsByPhone(request.phone())) {
            throw new BusinessRuleViolationException("RULE-01-10", "Số điện thoại đã được sử dụng");
        }

        Account account = new Account(request.email(), request.phone(), passwordEncoder.encode(request.password()));
        account.setStatus(AccountStatus.ACTIVE);
        account.setMustChangePassword(true);
        try {
            account = accountRepository.save(account);
        } catch (DataIntegrityViolationException ex) {
            throw duplicateAccountException(ex);
        }

        User user = userProvisioningService.createStaffProfile(account.getId(), request.name(), request.role(),
                request.organizationId(), request.storeId());

        accountEventRecorder.record(account, "AccountActivated");
        accountEventRecorder.record(account, "PermissionAssigned", Map.of("role", request.role().name()));

        return new CreateStaffResponse(account.getId(), user.getId(), account.getStatus(), account.isMustChangePassword());
    }

    /**
     * RULE-02-06 — Receptionist tạo hồ sơ customer tại quầy. ACTIVE ngay,
     * không OTP (Receptionist đã xác minh danh tính trực tiếp) —
     * mustChangePassword=true vì Receptionist đặt mật khẩu hộ khách (cùng
     * tinh thần D-04, ASSUMPTION — ngoài phạm vi CreateStaff nhưng hợp lý
     * suy ra vì lý do bảo mật tương tự). Không nhận organizationId/storeId —
     * Customer không gắn Organization/Store (RULE-02-02).
     */
    @Override
    @Transactional
    @Auditable(action = "CreateCustomer")
    public CreateCustomerResponse createCustomer(CreateCustomerRequest request) {
        if (request.password().length() < PASSWORD_MIN_LENGTH) {
            throw new BusinessRuleViolationException("RULE-01-09",
                    "Mật khẩu phải có ít nhất " + PASSWORD_MIN_LENGTH + " ký tự");
        }
        if (accountRepository.existsByEmail(request.email())) {
            throw new BusinessRuleViolationException("RULE-01-10", "Email đã được sử dụng");
        }
        if (hasPhone(request.phone()) && accountRepository.existsByPhone(request.phone())) {
            throw new BusinessRuleViolationException("RULE-01-10", "Số điện thoại đã được sử dụng");
        }

        Account account = new Account(request.email(), request.phone(), passwordEncoder.encode(request.password()));
        account.setStatus(AccountStatus.ACTIVE);
        account.setMustChangePassword(true);
        try {
            account = accountRepository.save(account);
        } catch (DataIntegrityViolationException ex) {
            throw duplicateAccountException(ex);
        }

        User user = userProvisioningService.createCustomerProfile(account.getId(), request.name());

        accountEventRecorder.record(account, "AccountActivated");

        return new CreateCustomerResponse(account.getId(), user.getId(), account.getStatus(), account.isMustChangePassword());
>>>>>>> 8bfc5bd (feat: triển khai module iam)
    }

    private String generateOtpCode() {
        int code = secureRandom.nextInt(1_000_000);
        return String.format("%06d", code);
    }

    private String buildResetEmailContent(String otpCode) {
        return "Mã đặt lại mật khẩu Pet Care của bạn là " + otpCode + ", hết hạn sau "
                + (OTP_TTL_SECONDS / 60) + " phút. Nếu bạn không yêu cầu, hãy bỏ qua email này.";
    }

    private String buildOtpEmailContent(String otpCode) {
        return "Mã xác thực Pet Care của bạn là " + otpCode + ", hết hạn sau "
                + (OTP_TTL_SECONDS / 60) + " phút. Không chia sẻ mã này cho bất kỳ ai.";
    }
}
