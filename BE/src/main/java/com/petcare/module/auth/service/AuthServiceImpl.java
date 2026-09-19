package com.petcare.module.auth.service;

import com.petcare.module.auth.dto.CreateCustomerRequest;
import com.petcare.module.auth.dto.CreateCustomerResponse;
import com.petcare.module.auth.dto.CreateStaffRequest;
import com.petcare.module.auth.dto.CreateStaffResponse;
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
import com.petcare.platform.exception.InvalidStateTransitionException;
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

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
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
        String email = normalizeEmail(request.email());
        if (request.password().length() < PASSWORD_MIN_LENGTH) {
            throw new BusinessRuleViolationException("RULE-01-09",
                    "Mật khẩu phải có ít nhất " + PASSWORD_MIN_LENGTH + " ký tự");
        }
        if (accountRepository.existsByEmail(email)) {
            throw new BusinessRuleViolationException("RULE-01-10", "Email đã được sử dụng");
        }
        if (hasPhone(request.phone()) && accountRepository.existsByPhone(request.phone())) {
            throw new BusinessRuleViolationException("RULE-01-10", "Số điện thoại đã được sử dụng");
        }

        Account account = new Account(email, request.phone(), passwordEncoder.encode(request.password()));
        try {
            account = accountRepository.save(account);
        } catch (DataIntegrityViolationException ex) {
            // §5.1 Concurrency: 2 request register cùng email/phone gần như đồng thời —
            // pre-check ở trên có thể đều thấy "chưa tồn tại" (race). UNIQUE constraint ở
            // DB là guard thật; request thua ở đây nhận lỗi nghiệp vụ sạch thay vì 500.
            throw duplicateAccountException(ex);
        }

        User user = userProvisioningService.createCustomerProfile(account.getId(), request.name());

        String otpCode = generateOtpCode();
        Otp otp = new Otp(email, otpCode, OtpPurpose.REGISTRATION,
                LocalDateTime.now().plusSeconds(OTP_TTL_SECONDS));
        otpRepository.save(otp);

        accountEventRecorder.record(account, "AccountRegistered", Map.of("otpId", String.valueOf(otp.getId())));

        var notificationTaskId = notificationService.enqueue(user.getId(), NotificationChannel.EMAIL,
                "OTP", buildOtpEmailContent(otpCode));

        return new RegistrationOutcome(account, notificationTaskId);
    }

    /**
     * noRollbackFor: sai OTP/khóa session (RULE-01-02/05) phải commit
     * attemptCount/lockedUntil dù method throw — mặc định Spring rollback
     * toàn bộ transaction trên RuntimeException sẽ xóa mất counter vừa
     * save() (bug thật, đã xảy ra). ConcurrencyConflictException/
     * ResourceNotFoundException KHÔNG extend BusinessRuleViolationException
     * nên vẫn rollback bình thường — bắt buộc, vì lúc đó Account activate
     * thất bại thì otp.used=true không được phép commit riêng lẻ. Khi sửa
     * method này, mọi save() mới đặt trước 1 throw BusinessRuleViolationException
     * (hoặc subclass) sẽ MẶC ĐỊNH được commit — cân nhắc kỹ trước khi thêm.
     */
    @Override
    @Transactional(noRollbackFor = BusinessRuleViolationException.class)
    public Account verifyOtp(VerifyOtpRequest request) {
        String email = normalizeEmail(request.email());
        // Khoá Account TRƯỚC Otp — cùng thứ tự khoá với resendOtp() (xem
        // AccountRepository#findByEmailForUpdate) để không đảo ngược lock order giữa 2
        // method, tránh deadlock khi verify-otp và resend-otp chạy đồng thời trên cùng email.
        Account account = accountRepository.findByEmailForUpdate(email)
                .orElseThrow(() -> new ResourceNotFoundException("Account", email));

        // Kiểm tra state-transition TRƯỚC khi đụng tới Otp (docs/api/auth-v1.md C2: verify khi
        // Account đã ACTIVE -> 409 INVALID_STATE_TRANSITION). Bắt buộc xét ở đây, TRƯỚC guard
        // otp.isUsed() bên dưới — OTP gốc luôn otp.used=true ngay khi account chuyển ACTIVE
        // (cùng transaction), nên nếu xét OTP trước, double-verify sẽ luôn bị chặn nhầm bằng
        // 400 RULE-01-02 ("OTP đã hết hạn") thay vì đúng 409 mà spec yêu cầu; đặt ở đây còn xử
        // lý đúng luôn cả trường hợp account ACTIVE nhưng chưa từng có Otp nào (CreateStaff/
        // CreateCustomer, D-04 bỏ qua OTP hoàn toàn) — trả 409 thay vì 404 "Otp not found".
        //
        // So sánh trực tiếp == PENDING_VERIFICATION thay vì gọi
        // accountTransitionHandler.validateTransition(status, ACTIVE): map FSM-1 dùng chung
        // cạnh "-> ACTIVE" cho nhiều command (LOCKED->ACTIVE của UnlockAccount, DEACTIVATED->ACTIVE
        // của ReactivateAccount — RULE-02-07 bắt buộc `reason` + audit riêng, xem
        // AccountLifecycleServiceImpl#unlockAccount cùng pattern), nên validateTransition() sẽ KHÔNG
        // throw nếu account đang LOCKED/DEACTIVATED — VerifyOTP sẽ vô tình "mở khoá"/"tái kích hoạt"
        // tài khoản bỏ qua toàn bộ yêu cầu RULE-02-04/07 nếu chẳng may vẫn còn 1 Otp hợp lệ. VerifyOTP
        // chỉ hợp lệ đúng 1 nguồn duy nhất là PENDING_VERIFICATION.
        if (account.getStatus() != AccountStatus.PENDING_VERIFICATION) {
            throw new InvalidStateTransitionException(accountTransitionHandler.getClass().getSimpleName(),
                    account.getStatus().name(), AccountStatus.ACTIVE.name());
        }

        Otp otp = otpRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(email, OtpPurpose.REGISTRATION)
                .orElseThrow(() -> new ResourceNotFoundException("Otp", email));

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

        account.setStatus(AccountStatus.ACTIVE);
        try {
            account = accountRepository.save(account);
        } catch (ObjectOptimisticLockingFailureException ex) {
            // §5.2 defense-in-depth — không nên xảy ra thật vì findByEmailForUpdate() đã giữ
            // PESSIMISTIC_WRITE trên đúng row Account này từ đầu method.
            throw new ConcurrencyConflictException("Account", account.getId());
        }

        accountEventRecorder.record(account, "AccountActivated");
        return account;
    }

    @Override
    @Transactional
    public RegistrationOutcome resendOtp(ResendOtpRequest request) {
        String email = normalizeEmail(request.email());
        // Khoá Account TRƯỚC Otp (xem AccountRepository#findByEmailForUpdate) — Account là
        // row ổn định, không bị "dời mục tiêu" khi method này tự insert thêm 1 Otp row mới,
        // nên khoá vào nó serialize được trọn vẹn đoạn đọc-Otp-hiện-tại/tạo-Otp-mới bên dưới,
        // tránh 2 resend đồng thời cùng tạo ra 2 OTP active (RULE-01-04). Giữ đúng thứ tự
        // khoá Account-trước-Otp giống verifyOtp() để không đảo ngược lock order (deadlock).
        Account account = accountRepository.findByEmailForUpdate(email)
                .orElseThrow(() -> new ResourceNotFoundException("Account", email));
        if (account.getStatus() != AccountStatus.PENDING_VERIFICATION) {
            throw new BusinessRuleViolationException("RULE-01-03",
                    "Tài khoản đã xác thực, không cần gửi lại OTP");
        }

        Optional<Otp> currentOtp = otpRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(
                email, OtpPurpose.REGISTRATION);

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

        // RULE-01-05 đếm trực tiếp số OTP do ResendOTP tạo (is_resend=true) trong cửa sổ
        // rolling 1h — không tính OTP gốc phát sinh từ RegisterAccount, dù nó còn hay đã
        // văng khỏi cửa sổ.
        long resendCountInLastHour = otpRepository.countByEmailAndPurposeAndResendTrueAndCreatedAtAfter(
                email, OtpPurpose.REGISTRATION, LocalDateTime.now().minusHours(1));
        if (resendCountInLastHour >= OTP_MAX_RESEND_PER_HOUR) {
            throw new BusinessRuleViolationException("RULE-01-05",
                    "Đã vượt giới hạn gửi lại OTP trong 1 giờ");
        }

        currentOtp.filter(otp -> !otp.isUsed()).ifPresent(otp -> {
            otp.setUsed(true);
            otpRepository.save(otp);
        });

        String otpCode = generateOtpCode();
        Otp newOtp = new Otp(email, otpCode, OtpPurpose.REGISTRATION,
                LocalDateTime.now().plusSeconds(OTP_TTL_SECONDS));
        newOtp.setResend(true);
        otpRepository.save(newOtp);

        User user = userProvisioningService.findByAccountId(account.getId());
        var notificationTaskId = notificationService.enqueue(user.getId(), NotificationChannel.EMAIL,
                "OTP", buildOtpEmailContent(otpCode));

        return new RegistrationOutcome(account, notificationTaskId);
    }

    /**
     * noRollbackFor: sai mật khẩu/khóa account (RULE-01-01/07) phải commit
     * failedLoginAttempts/status=LOCKED dù method throw — cùng lý do như
     * verifyOtp ở trên. Không có noRollbackFor này, RULE-01-07 (khóa sau 5
     * lần sai) không bao giờ có hiệu lực thật vì counter luôn bị rollback về
     * giá trị cũ. ConcurrencyConflictException vẫn rollback bình thường (không
     * extend BusinessRuleViolationException) — đúng, vì reset counter bị
     * conflict thì không nên commit dở dang.
     */
    @Override
    @Transactional(noRollbackFor = BusinessRuleViolationException.class)
    public LoginResponse login(LoginRequest request, String userAgent, String ipAddress) {
        Account account = accountRepository.findByEmail(normalizeEmail(request.email()))
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
        boolean revoked = tokenIssuanceFacade.logout(rawAccessToken, refreshToken);
        return new LogoutResponse(revoked);
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
        // saveWithConcurrencyCheck (không phải save thường) — 2 request login/refresh gần như
        // đồng thời cùng qua điều kiện auto-unlock (locked_until vừa hết hạn) có thể cùng đọc
        // 1 version rồi cùng ghi đè; request thua cuộc phải nhận 409 CONCURRENCY_CONFLICT sạch
        // thay vì rơi xuống handleGeneric (500) — cùng lý do như registerFailedLoginAttempt().
        saveWithConcurrencyCheck(account);
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
     * RULE-01-10 — chuẩn hoá email (trim + lowercase) TRƯỚC khi dùng để tạo/tra cứu Account
     * hoặc Otp. Postgres so khớp VARCHAR phân biệt hoa/thường và không có normalization nào
     * khác trong module này, nên nếu không chuẩn hoá ở đây, "User@Gmail.com" lúc đăng ký và
     * "user@gmail.com" lúc verify-otp/login (rất dễ khác nhau do auto-capitalize trên bàn
     * phím mobile) sẽ bị coi là 2 danh tính khác nhau — verify-otp trả 404 dù đúng tài
     * khoản/mã OTP, hoặc login trả sai credentials và có thể dẫn tới tự khoá tài khoản oan
     * sau 5 lần "sai" liên tiếp (RULE-01-07).
     */
    private String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase();
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

    @Override
    @Transactional
    @Auditable(action = "CreateStaff", resourceType = "Account")
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

        String email = normalizeEmail(request.email());
        if (request.password().length() < PASSWORD_MIN_LENGTH) {
            throw new BusinessRuleViolationException("RULE-01-09",
                    "Mật khẩu phải có ít nhất " + PASSWORD_MIN_LENGTH + " ký tự");
        }
        if (accountRepository.existsByEmail(email)) {
            throw new BusinessRuleViolationException("RULE-01-10", "Email đã được sử dụng");
        }
        if (hasPhone(request.phone()) && accountRepository.existsByPhone(request.phone())) {
            throw new BusinessRuleViolationException("RULE-01-10", "Số điện thoại đã được sử dụng");
        }

        Account account = new Account(email, request.phone(), passwordEncoder.encode(request.password()));
        account.setStatus(AccountStatus.ACTIVE);
        account.setMustChangePassword(true);
        try {
            account = accountRepository.save(account);
        } catch (DataIntegrityViolationException ex) {
            throw duplicateAccountException(ex);
        }

        User user;
        try {
            user = userProvisioningService.createStaffProfile(account.getId(), request.name(), request.role(),
                    request.organizationId(), request.storeId());
        } catch (DataIntegrityViolationException ex) {
            // RoleScopeGuard.validateRoleScopeBinding (gọi trong assertCanAssignRole ở trên)
            // chỉ kiểm tra HÌNH DẠNG binding theo role (RULE-02-02: role này có bắt buộc
            // organizationId/storeId hay không), không kiểm tra UUID đó có THỰC SỰ TỒN TẠI
            // trong bảng organizations/stores hay không — Module 03 (Store) hiện chưa có API
            // tạo Store thật nên storeId truyền vào luôn "ảo". Nếu không bắt ở đây, FK
            // violation (fk_users_org/fk_users_store) sẽ rơi xuống handleGeneric -> 500 thay
            // vì lỗi rõ ràng cho client biết đúng Organization/Store nào không tồn tại.
            throw staffProvisioningFkViolationException(ex, request.organizationId(), request.storeId());
        }

        accountEventRecorder.record(account, "AccountActivated");
        accountEventRecorder.record(account, "PermissionAssigned", Map.of("role", request.role().name()));

        return new CreateStaffResponse(account.getId(), user.getId(), account.getStatus(), account.isMustChangePassword());
    }

    private ResourceNotFoundException staffProvisioningFkViolationException(DataIntegrityViolationException ex,
                                                                             UUID organizationId, UUID storeId) {
        Throwable cause = ex.getMostSpecificCause();
        String detail = cause == null ? null : cause.getMessage();
        if (detail != null && detail.contains("fk_users_store")) {
            return new ResourceNotFoundException("Store", storeId);
        }
        return new ResourceNotFoundException("Organization", organizationId);
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
    @Auditable(action = "CreateCustomer", resourceType = "Account")
    public CreateCustomerResponse createCustomer(CreateCustomerRequest request) {
        String email = normalizeEmail(request.email());
        if (request.password().length() < PASSWORD_MIN_LENGTH) {
            throw new BusinessRuleViolationException("RULE-01-09",
                    "Mật khẩu phải có ít nhất " + PASSWORD_MIN_LENGTH + " ký tự");
        }
        if (accountRepository.existsByEmail(email)) {
            throw new BusinessRuleViolationException("RULE-01-10", "Email đã được sử dụng");
        }
        if (hasPhone(request.phone()) && accountRepository.existsByPhone(request.phone())) {
            throw new BusinessRuleViolationException("RULE-01-10", "Số điện thoại đã được sử dụng");
        }

        Account account = new Account(email, request.phone(), passwordEncoder.encode(request.password()));
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
    }

    private String generateOtpCode() {
        int code = secureRandom.nextInt(1_000_000);
        return String.format("%06d", code);
    }

    private String buildOtpEmailContent(String otpCode) {
        return "Mã xác thực Pet Care của bạn là " + otpCode + ", hết hạn sau "
                + (OTP_TTL_SECONDS / 60) + " phút. Không chia sẻ mã này cho bất kỳ ai.";
    }
}
