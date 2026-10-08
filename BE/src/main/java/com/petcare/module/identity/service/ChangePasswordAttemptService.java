package com.petcare.module.identity.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.care.api.NotificationApi;
import com.petcare.module.care.api.NotificationApi.Channel;
import com.petcare.module.care.api.NotificationApi.NotificationRequest;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.NotificationTemplateCode;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.identity.entity.Account;
import com.petcare.module.identity.entity.AccountStatus;
import com.petcare.module.identity.exception.CurrentPasswordMismatchException;
import com.petcare.module.identity.repository.AccountPasswordState;
import com.petcare.module.identity.repository.AccountRepository;
import com.petcare.module.identity.service.LoginAttemptService.LoginCounterSnapshot;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.exception.BusinessRuleViolationException;

import lombok.extern.slf4j.Slf4j;

/**
 * Phần có transaction của UC05 — đổi mật khẩu (docs/adr/0022). {@link ChangePasswordService} (không transaction) đọc
 * {@link #findPasswordState}, so BCrypt và mã hóa mật khẩu mới khi không giữ connection, rồi gọi {@link #apply}.
 * <ul>
 *   <li>Mật khẩu hiện tại sai → {@link CurrentPasswordMismatchException} (400 BR-TK-14): bộ đếm BR-TK-09 (dùng chung
 *       với đăng nhập), khóa tạm, email cảnh báo, audit được <b>commit</b> nhờ {@code noRollbackFor}. Chỉ ném trực
 *       tiếp ở đây, sau mọi lệnh ghi.</li>
 *   <li>BR-TK-11 / BR-TK-09 / BR-TK-03 ném <b>trước</b> mọi lệnh ghi nên rollback sạch.</li>
 *   <li>Đúng → hash mới, gỡ {@code must_change_password}, xóa bộ đếm, hủy mọi phiên khác (BR-TK-14, 17).</li>
 * </ul>
 * Thứ tự khóa {@code accounts → sessions} (docs/adr/0021): dòng {@code accounts} bị khóa ngay ở
 * {@code findByIdForUpdate}; thay đổi entity được flush trước câu hủy phiên ({@code flushAutomatically}).
 */
@Slf4j
@Service
public class ChangePasswordAttemptService {

    static final String MSG_JUST_LOCKED =
            CurrentPasswordMismatchException.MESSAGE + ". Bạn đã nhập sai quá nhiều lần, tài khoản tạm khóa đăng nhập tới %s";

    private final AccountRepository accounts;
    private final SessionService sessions;
    private final NotificationApi notifications;
    private final SystemConfigApi configs;
    private final AuditRecorder audit;
    private final PasswordEncoder passwordEncoder;
    private final NewPasswordHasher newPasswordHasher;
    private final Clock clock;

    public ChangePasswordAttemptService(AccountRepository accounts, SessionService sessions,
            NotificationApi notifications, SystemConfigApi configs, AuditRecorder audit,
            PasswordEncoder passwordEncoder, NewPasswordHasher newPasswordHasher, Clock clock) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.notifications = notifications;
        this.configs = configs;
        this.audit = audit;
        this.passwordEncoder = passwordEncoder;
        this.newPasswordHasher = newPasswordHasher;
        this.clock = clock;
    }

    /** Bước đọc không khóa: transaction readOnly ngắn, trả connection trước khi so BCrypt (docs/adr/0022). */
    @Transactional(readOnly = true)
    public AccountPasswordState findPasswordState(Long accountId) {
        return accounts.findPasswordStateById(accountId)
                .orElseThrow(() -> new IllegalStateException("Authenticated account " + accountId + " not found"));
    }

    /**
     * Phần còn lại của UC05 dưới khóa dòng {@code accounts}.
     *
     * @param readPasswordHash hash đã đọc ở {@link #findPasswordState}, dùng để phát hiện mật khẩu vừa đổi song song
     * @param passwordMatched  kết quả so BCrypt của {@code currentPassword} với {@code readPasswordHash}
     * @param newPasswordHash  hash của {@code newPassword} khi {@code passwordMatched}, {@code null} nếu không
     */
    @Transactional(noRollbackFor = CurrentPasswordMismatchException.class)
    public void apply(Long accountId, Long sessionId, String readPasswordHash, String currentPassword,
            String newPassword, boolean passwordMatched, String newPasswordHash) {
        Instant now = Instant.now(clock);
        Account account = accounts.findByIdForUpdate(accountId)
                .orElseThrow(() -> new IllegalStateException("Authenticated account " + accountId + " not found"));

        // BR-TK-11: tài khoản vừa bị khóa / vô hiệu hóa sau khi filter đã cho request qua (docs/adr/0022 mục 9).
        if (account.isLocked() || account.getStatus() != AccountStatus.ACTIVE) {
            throw new BusinessRuleViolationException("BR-TK-11", LoginAttemptService.MSG_LOCKED);
        }
        // BR-TK-09: đăng nhập song song vừa kích hoạt khóa tạm sau bước đọc.
        if (account.isTemporarilyLocked(now)) {
            throw ChangePasswordService.temporarilyLocked(account.getLockedUntil());
        }

        boolean matched = passwordMatched;
        String newHash = newPasswordHash;
        if (!account.getPasswordHash().equals(readPasswordHash)) {
            // Mật khẩu vừa đổi / đặt lại từ phiên khác: so lại với hash đọc dưới khóa (hiếm).
            matched = LoginAttemptService.fitsBcrypt(currentPassword)
                    && passwordEncoder.matches(currentPassword, account.getPasswordHash());
            if (matched && newHash == null) {
                newHash = newPasswordHasher.hash(currentPassword, newPassword);
            }
        }

        if (!matched) {
            recordFailure(account, now);   // luôn ném CurrentPasswordMismatchException sau các lệnh ghi
        }

        account.changePassword(newHash, now);
        sessions.revokeOthers(accountId, sessionId);
        log.info("PASSWORD_CHANGED accountId={} sessionId={}", accountId, sessionId);
    }

    /** BR-TK-14 → BR-TK-09: cộng bộ đếm dùng chung với đăng nhập; chạm ngưỡng thì khóa tạm, email, audit. */
    private void recordFailure(Account account, Instant now) {
        LoginCounterSnapshot before = LoginCounterSnapshot.of(account);
        int maxAttempts = configs.getInt(ConfigKey.LOGIN_MAX_FAILED_ATTEMPTS);
        boolean justLocked = account.recordFailedLogin(now,
                Duration.ofMinutes(configs.getInt(ConfigKey.LOGIN_FAILED_WINDOW_MINUTES)), maxAttempts,
                Duration.ofMinutes(configs.getInt(ConfigKey.LOGIN_LOCK_MINUTES)));
        if (!justLocked) {
            throw new CurrentPasswordMismatchException(CurrentPasswordMismatchException.MESSAGE);
        }
        String unlockTime = LoginAttemptService.formatUnlockTime(account.getLockedUntil());
        // Cùng mẫu và payload với đăng nhập (LoginAttemptService.recordFailure).
        notifications.enqueue(new NotificationRequest(NotificationTemplateCode.LOGIN_LOCKED_WARNING, Channel.EMAIL,
                null, account.getEmail(), Map.of("so_lan_sai", maxAttempts, "thoi_diem_mo_khoa", unlockTime), null));
        audit.record(AuditEntry.of(IdentityAuditActions.ACCOUNT_TEMPORARILY_LOCKED)
                .entity("accounts", account.getId())
                .before(before)
                .after(LoginCounterSnapshot.of(account)));
        log.info("ACCOUNT_TEMPORARILY_LOCKED accountId={} source=CHANGE_PASSWORD", account.getId());
        throw new CurrentPasswordMismatchException(MSG_JUST_LOCKED.formatted(unlockTime));
    }
}
