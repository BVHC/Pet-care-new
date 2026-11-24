package com.petcare.module.identity.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
import com.petcare.module.identity.service.SessionService.OpenedSession;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.security.PasswordConfig;

import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;

/**
 * Phần có transaction của UC03 — đăng nhập (docs/adr/0019). {@link LoginService} (không transaction) đọc
 * {@link #findCredential}, so BCrypt khi không giữ connection, rồi gọi {@link #login} hoặc {@link #rejectUnknownEmail}.
 * <ul>
 *   <li>Sai thông tin → {@link InvalidCredentialsException} (401): bộ đếm, khóa tạm, email cảnh báo, audit được
 *       <b>commit</b> nhờ {@code noRollbackFor} (mục 3). Exception này chỉ ném trực tiếp ở đây, sau mọi lệnh ghi.</li>
 *   <li>Mật khẩu đúng nhưng bị chặn → {@link LoginRejectedException} (400 BR-TK-08/09/11) ném <b>trước</b> mọi lệnh ghi
 *       nên rollback sạch; {@code LoginService} ghi audit sau rollback (mục 6).</li>
 * </ul>
 * Thứ tự kiểm tra (mục 1): tồn tại → khóa tạm → mật khẩu → trạng thái, nên BR-TK-08/09/11 chỉ lộ khi đúng mật khẩu
 * (BR-TK-10).
 * <p>
 * {@link #loginWithGoogle}: phần có transaction của đăng nhập Google (docs/adr/0152), gọi từ {@link GoogleLoginService}
 * sau khi token đã kiểm ngoài transaction; cùng quy ước 401 / 400 như trên.
 */
@Slf4j
@Service
public class LoginAttemptService {

    static final String MSG_TEMPORARILY_LOCKED =
            "Tài khoản tạm khóa do đăng nhập sai nhiều lần. Vui lòng thử lại sau %s";
    static final String MSG_PENDING = "Tài khoản chưa xác thực email. Vui lòng nhập mã OTP đã gửi tới email";
    static final String MSG_LOCKED = "Tài khoản đã bị khóa. Vui lòng liên hệ Pet Care để được hỗ trợ";
    static final String MSG_GOOGLE_STAFF = "Tài khoản nhân viên đăng nhập bằng email và mật khẩu";
    static final String MSG_GOOGLE_EMAIL_TAKEN =
            "Email này đã có tài khoản. Vui lòng đăng nhập bằng email và mật khẩu";

    /** {@code customers.full_name VARCHAR(100)}: Postgres đếm theo ký tự (code point), không theo char UTF-16. */
    static final int FULL_NAME_MAX_CODE_POINTS = 100;

    /** Định dạng giờ mở khóa trong message BR-TK-09 và email {@code LOGIN_LOCKED_WARNING} (V8). */
    static final DateTimeFormatter UNLOCK_TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy").withZone(TimeConfig.BUSINESS_ZONE);

    /** Snapshot audit của bộ đếm (BR-QT-15 "giá trị trước và sau"); không có khóa nhạy cảm. */
    record LoginCounterSnapshot(int failedLoginCount, Instant lockedUntil) {
        static LoginCounterSnapshot of(Account account) {
            return new LoginCounterSnapshot(account.getFailedLoginCount(), account.getLockedUntil());
        }
    }

    private final AccountRepository accounts;
    private final SessionService sessions;
    private final CustomerQueryApi customers;
    private final NotificationApi notifications;
    private final SystemConfigApi configs;
    private final AuditRecorder audit;
    private final PasswordEncoder passwordEncoder;
    private final LoginMapper mapper;
    private final Clock clock;
    private final CustomerApi customerProfiles;
    private final OtpTokenRepository otpTokens;
    private final AccountTransitionHandler transitions;
    private final EntityManager entityManager;

    public LoginAttemptService(AccountRepository accounts, SessionService sessions, CustomerQueryApi customers,
            NotificationApi notifications, SystemConfigApi configs, AuditRecorder audit,
            PasswordEncoder passwordEncoder, LoginMapper mapper, Clock clock, CustomerApi customerProfiles,
            OtpTokenRepository otpTokens, AccountTransitionHandler transitions, EntityManager entityManager) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.customers = customers;
        this.notifications = notifications;
        this.configs = configs;
        this.audit = audit;
        this.passwordEncoder = passwordEncoder;
        this.mapper = mapper;
        this.clock = clock;
        this.customerProfiles = customerProfiles;
        this.otpTokens = otpTokens;
        this.transitions = transitions;
        this.entityManager = entityManager;
    }

    /**
     * Bước đọc không khóa: transaction readOnly ngắn, trả connection trước khi {@link LoginService} so BCrypt
     * (docs/adr/0019 mục 4).
     */
    @Transactional(readOnly = true)
    public Optional<AccountCredential> findCredential(String email) {
        return accounts.findCredentialByEmail(email);
    }

    /** Email không khớp tài khoản nào: ghi {@code LOGIN_FAILED} rồi luôn ném 401 (transaction vẫn commit). */
    @Transactional(noRollbackFor = InvalidCredentialsException.class)
    public void rejectUnknownEmail(String email) {
        audit.record(unknownEmailEntry(email));
        throw new InvalidCredentialsException();
    }

    /**
     * Phần còn lại của UC03 dưới khóa dòng {@code accounts} (docs/adr/0019 mục 1, 2, 4).
     *
     * @param passwordMatched kết quả so BCrypt ở {@link LoginService} với {@code credential.passwordHash()}
     */
    @Transactional(noRollbackFor = InvalidCredentialsException.class)
    public LoginResponse login(AccountCredential credential, String email, String password, boolean passwordMatched,
            String ipAddress, String userAgent) {
        Instant now = Instant.now(clock);
        Optional<Account> locked = accounts.findByIdForUpdate(credential.id());
        if (locked.isEmpty()) {
            // ST02 vừa xóa tài khoản PENDING quá hạn giữa bước đọc và bước khóa: như email lạ.
            audit.record(unknownEmailEntry(email));
            throw new InvalidCredentialsException();
        }
        Account account = locked.get();
        boolean matched = passwordMatched;
        if (!account.getPasswordHash().equals(credential.passwordHash())) {
            // Mật khẩu vừa đổi / đặt lại song song: so lại với hash đọc dưới khóa (hiếm).
            matched = fitsBcrypt(password) && passwordEncoder.matches(password, account.getPasswordHash());
        }

        if (account.isTemporarilyLocked(now)) {
            AuditEntry entry = failedEntry(account, IdentityAuditActions.REASON_TEMPORARILY_LOCKED);
            if (matched) {
                throw new LoginRejectedException("BR-TK-09",
                        MSG_TEMPORARILY_LOCKED.formatted(formatUnlockTime(account.getLockedUntil())), entry);
            }
            audit.record(entry);   // sai trong lúc khóa: không đếm (docs/adr/0019 mục 2)
            throw new InvalidCredentialsException();
        }

        if (!matched) {
            recordFailure(account, now);
            throw new InvalidCredentialsException();
        }

        if (account.getStatus() == AccountStatus.PENDING) {
            throw new LoginRejectedException("BR-TK-08", MSG_PENDING,
                    failedEntry(account, IdentityAuditActions.REASON_PENDING));
        }
        if (account.isLocked()) {
            throw new LoginRejectedException("BR-TK-11", MSG_LOCKED,
                    failedEntry(account, IdentityAuditActions.REASON_LOCKED));
        }
        if (account.getStatus() == AccountStatus.DISABLED) {
            throw new LoginRejectedException("BR-TK-11", MSG_LOCKED,
                    failedEntry(account, IdentityAuditActions.REASON_DISABLED));
        }

        return succeed(account, now, ipAddress, userAgent, Map.of());
    }

    /**
     * Đăng nhập / tạo tài khoản bằng Google (docs/adr/0152, spec 2026-11-24 §6.2, §6.3). {@code identity} đã kiểm ở
     * {@link GoogleLoginService} (ngoài transaction). Khóa {@code accounts} theo {@code sub} trước, không thấy mới theo
     * email; mỗi request khóa đúng một dòng, đọc dưới khóa. Mọi nhánh 400 ném trước lệnh ghi đầu tiên; 401 lệch
     * {@code sub} ghi audit rồi ném (commit nhờ {@code noRollbackFor}).
     *
     * @param unusableHash BCrypt không ai biết mật khẩu, tính sẵn ngoài transaction, lưu cho tài khoản tạo mới
     */
    @Transactional(noRollbackFor = InvalidCredentialsException.class)
    public LoginResponse loginWithGoogle(GoogleIdentity identity, GoogleLoginRequest request, String unusableHash,
            String ipAddress, String userAgent) {
        Instant now = Instant.now(clock);
        Optional<Account> bound = accounts.findByGoogleSubForUpdate(identity.sub());
        if (bound.isPresent()) {
            // sub thắng: email tài khoản giữ nguyên, tài khoản đang giữ email của token (nếu có) không bị đụng.
            rejectIfBlocked(bound.get(), now);
            return succeed(bound.get(), now, ipAddress, userAgent, Map.of("method", IdentityAuditActions.METHOD_GOOGLE));
        }

        Optional<Account> byEmail = accounts.findByEmailForUpdate(identity.email());
        if (byEmail.isEmpty()) {
            requireConsent(request);
            return createGoogleCustomer(identity, request, unusableHash, now, ipAddress, userAgent);
        }
        Account account = byEmail.get();
        if (account.getRole() != Role.CUSTOMER) {
            throw new LoginRejectedException("BR-TK-17", MSG_GOOGLE_STAFF,
                    failedEntry(account, IdentityAuditActions.REASON_GOOGLE_STAFF));
        }
        // So sánh, không chỉ kiểm null: request song song cùng token có thể vừa gắn đúng sub này rồi commit trước
        // khi khóa theo email nhả (bước tìm theo sub ở trên đã chạy trước commit đó).
        if (account.getGoogleSub() != null && !account.getGoogleSub().equals(identity.sub())) {
            audit.record(failedEntry(account, IdentityAuditActions.REASON_GOOGLE_SUB_MISMATCH));
            throw new InvalidCredentialsException(InvalidCredentialsException.GOOGLE_MESSAGE);
        }
        if (!identity.emailAuthoritative()) {
            throw new LoginRejectedException("BR-TK-01", MSG_GOOGLE_EMAIL_TAKEN,
                    failedEntry(account, IdentityAuditActions.REASON_GOOGLE_EMAIL_NOT_AUTHORITATIVE));
        }
        if (account.getStatus() != AccountStatus.PENDING) {
            rejectIfBlocked(account, now);
            account.bindGoogle(identity.sub());
            return succeed(account, now, ipAddress, userAgent, Map.of("method", IdentityAuditActions.METHOD_GOOGLE));
        }

        // PENDING: không kích hoạt tại chỗ (mật khẩu có thể của người đăng ký trước). Không áp BR-TK-09/11: kẻ đăng ký
        // trước gõ sai 5 lần là chặn được chủ thật. Xóa như ST02 rồi tạo lại.
        requireConsent(request);
        otpTokens.deleteByAccountId(account.getId());
        customerProfiles.deleteOnlineProfileOfUnverifiedAccount(account.getId());
        if (accounts.deletePendingById(account.getId()) != 1) {
            throw new IllegalStateException("Tài khoản PENDING #" + account.getId() + " đã khóa nhưng không xóa được");
        }
        entityManager.detach(account);   // entity cũ không được dính vào flush của tài khoản mới cùng email
        return createGoogleCustomer(identity, request, unusableHash, now, ipAddress, userAgent);
    }

    /** Tạo tài khoản khách bằng Google: cạnh FSM có sẵn #1 rồi #2, không OTP, không email (docs/adr/0152). */
    private LoginResponse createGoogleCustomer(GoogleIdentity identity, GoogleLoginRequest request,
            String unusableHash, Instant now, String ipAddress, String userAgent) {
        transitions.validateInitial(AccountStatus.PENDING);
        Account account = Account.registerCustomer(identity.email(), unusableHash,
                now.plus(Duration.ofHours(configs.getInt(ConfigKey.ACCOUNT_PENDING_TTL_HOURS))));
        transitions.validateTransition(AccountStatus.PENDING, AccountStatus.ACTIVE);
        account.verify();
        account.bindGoogle(identity.sub());
        account = accounts.save(account);
        customerProfiles.createOnlineProfile(account.getId(), googleFullName(identity), request.phone());
        if (request.phone() != null) {
            customerProfiles.flagLinkDecisionIfPhoneMatches(account.getId());
        }
        log.info("ACCOUNT_REGISTERED_WITH_GOOGLE accountId={}", account.getId());
        return succeed(account, now, ipAddress, userAgent,
                Map.of("method", IdentityAuditActions.METHOD_GOOGLE, "accountCreated", true));
    }

    /** BR-TK-09, 11 khi đăng nhập bằng Google: Google đã chứng minh danh tính, nên như mật khẩu đúng. */
    private static void rejectIfBlocked(Account account, Instant now) {
        if (account.isTemporarilyLocked(now)) {
            throw new LoginRejectedException("BR-TK-09",
                    MSG_TEMPORARILY_LOCKED.formatted(formatUnlockTime(account.getLockedUntil())),
                    failedEntry(account, IdentityAuditActions.REASON_TEMPORARILY_LOCKED));
        }
        if (account.isLocked()) {
            throw new LoginRejectedException("BR-TK-11", MSG_LOCKED,
                    failedEntry(account, IdentityAuditActions.REASON_LOCKED));
        }
    }

    private static void requireConsent(GoogleLoginRequest request) {
        if (!Boolean.TRUE.equals(request.isAdult()) || !Boolean.TRUE.equals(request.termsAccepted())) {
            throw new BusinessRuleViolationException("BR-TK-02", RegistrationService.MSG_CONSENT_REQUIRED);
        }
    }

    /** Claim {@code name} (verifier đã ghép given + family), trống thì phần trước {@code @}; tối đa 100 code point. */
    static String googleFullName(GoogleIdentity identity) {
        String name = identity.name() == null || identity.name().isBlank()
                ? identity.email().substring(0, identity.email().indexOf('@'))
                : identity.name().strip();
        if (name.codePointCount(0, name.length()) <= FULL_NAME_MAX_CODE_POINTS) {
            return name;
        }
        return name.substring(0, name.offsetByCodePoints(0, FULL_NAME_MAX_CODE_POINTS)).strip();
    }

    /** Mật khẩu quá 72 byte không bao giờ khớp: BCrypt chỉ so 72 byte đầu (docs/adr/0019 mục 7). */
    static boolean fitsBcrypt(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length <= PasswordConfig.BCRYPT_MAX_BYTES;
    }

    /** Giờ mở khóa làm tròn lên phút, để người dùng không thử lại sớm hơn giờ mở thật. */
    static String formatUnlockTime(Instant lockedUntil) {
        Instant minute = lockedUntil.truncatedTo(ChronoUnit.MINUTES);
        if (minute.isBefore(lockedUntil)) {
            minute = minute.plus(1, ChronoUnit.MINUTES);
        }
        return UNLOCK_TIME_FORMAT.format(minute);
    }

    private void recordFailure(Account account, Instant now) {
        LoginCounterSnapshot before = LoginCounterSnapshot.of(account);
        int maxAttempts = configs.getInt(ConfigKey.LOGIN_MAX_FAILED_ATTEMPTS);
        boolean justLocked = account.recordFailedLogin(now,
                Duration.ofMinutes(configs.getInt(ConfigKey.LOGIN_FAILED_WINDOW_MINUTES)), maxAttempts,
                Duration.ofMinutes(configs.getInt(ConfigKey.LOGIN_LOCK_MINUTES)));
        if (justLocked) {
            // Không truyền recipientAccountId: tài khoản có thể đang PENDING (FK chặn ST02 xóa — NotificationApi).
            notifications.enqueue(new NotificationRequest(NotificationTemplateCode.LOGIN_LOCKED_WARNING,
                    Channel.EMAIL, null, account.getEmail(),
                    Map.of("so_lan_sai", maxAttempts,
                            "thoi_diem_mo_khoa", formatUnlockTime(account.getLockedUntil())),
                    null));
        }
        audit.record(failedEntry(account, IdentityAuditActions.REASON_BAD_CREDENTIALS)
                .before(before).after(LoginCounterSnapshot.of(account)));
    }

    /** @param extraAfter khóa thêm vào {@code LOGIN_SUCCEEDED.after} (Google: {@code method}, {@code accountCreated}) */
    private LoginResponse succeed(Account account, Instant now, String ipAddress, String userAgent,
            Map<String, Object> extraAfter) {
        LoginCounterSnapshot before = LoginCounterSnapshot.of(account);
        account.recordSuccessfulLogin(now);
        LoginCounterSnapshot after = LoginCounterSnapshot.of(account);
        OpenedSession session = sessions.open(account.getId(), ipAddress, userAgent);
        boolean linkDecisionPending = account.getRole() == Role.CUSTOMER && linkDecisionPending(account.getId());

        Map<String, Object> afterData = new LinkedHashMap<>();
        afterData.put("sessionId", session.sessionId());
        afterData.putAll(extraAfter);
        AuditEntry entry = AuditEntry.of(IdentityAuditActions.LOGIN_SUCCEEDED)
                .entity("accounts", account.getId())
                .actor(account.getId(), account.getEmail());
        if (!before.equals(after)) {
            afterData.put("failedLoginCount", after.failedLoginCount());
            afterData.put("lockedUntil", after.lockedUntil());
            entry = entry.before(before);
        }
        audit.record(entry.after(afterData));

        log.info("LOGIN_SUCCEEDED accountId={} sessionId={}", account.getId(), session.sessionId());
        return mapper.toLoginResponse(session, account, linkDecisionPending);
    }

    /** BR-TK-19: cờ chờ quyết định liên kết của hồ sơ khách (BR-KH-01: tài khoản khách luôn có hồ sơ). */
    private boolean linkDecisionPending(Long accountId) {
        Long customerId = customers.findCustomerIdByAccountId(accountId)
                .orElseThrow(() -> new IllegalStateException(
                        "Customer account " + accountId + " has no customer profile (BR-KH-01)"));
        return customers.findContact(customerId)
                .map(CustomerContact::linkDecisionPending)
                .orElseThrow(() -> new IllegalStateException("Customer " + customerId + " not found"));
    }

    private static AuditEntry unknownEmailEntry(String email) {
        return AuditEntry.of(IdentityAuditActions.LOGIN_FAILED)
                .actor(null, email)
                .reason(IdentityAuditActions.REASON_UNKNOWN_EMAIL);
    }

    private static AuditEntry failedEntry(Account account, String reason) {
        LoginCounterSnapshot counter = LoginCounterSnapshot.of(account);
        return AuditEntry.of(IdentityAuditActions.LOGIN_FAILED)
                .entity("accounts", account.getId())
                .actor(account.getId(), account.getEmail())
                .before(counter)
                .after(counter)
                .reason(reason);
    }
}
