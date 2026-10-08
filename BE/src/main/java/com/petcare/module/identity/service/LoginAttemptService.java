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
import com.petcare.module.identity.service.SessionService.OpenedSession;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.security.PasswordConfig;

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
 */
@Slf4j
@Service
public class LoginAttemptService {

    static final String MSG_TEMPORARILY_LOCKED =
            "Tài khoản tạm khóa do đăng nhập sai nhiều lần. Vui lòng thử lại sau %s";
    static final String MSG_PENDING = "Tài khoản chưa xác thực email. Vui lòng nhập mã OTP đã gửi tới email";
    static final String MSG_LOCKED = "Tài khoản đã bị khóa. Vui lòng liên hệ Pet Care để được hỗ trợ";

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

    public LoginAttemptService(AccountRepository accounts, SessionService sessions, CustomerQueryApi customers,
            NotificationApi notifications, SystemConfigApi configs, AuditRecorder audit,
            PasswordEncoder passwordEncoder, LoginMapper mapper, Clock clock) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.customers = customers;
        this.notifications = notifications;
        this.configs = configs;
        this.audit = audit;
        this.passwordEncoder = passwordEncoder;
        this.mapper = mapper;
        this.clock = clock;
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

        return succeed(account, now, ipAddress, userAgent);
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

    private LoginResponse succeed(Account account, Instant now, String ipAddress, String userAgent) {
        LoginCounterSnapshot before = LoginCounterSnapshot.of(account);
        account.recordSuccessfulLogin(now);
        LoginCounterSnapshot after = LoginCounterSnapshot.of(account);
        OpenedSession session = sessions.open(account.getId(), ipAddress, userAgent);
        boolean linkDecisionPending = account.getRole() == Role.CUSTOMER && linkDecisionPending(account.getId());

        Map<String, Object> afterData = new LinkedHashMap<>();
        afterData.put("sessionId", session.sessionId());
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
