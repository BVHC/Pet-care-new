package com.petcare.module.identity.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.care.api.NotificationApi;
import com.petcare.module.care.api.NotificationApi.Channel;
import com.petcare.module.care.api.NotificationApi.NotificationRequest;
import com.petcare.module.identity.api.NotificationTemplateCode;
import com.petcare.module.identity.entity.Account;
import com.petcare.module.identity.entity.AccountStatus;
import com.petcare.module.identity.entity.OtpPurpose;
import com.petcare.module.identity.exception.OtpRejectedException;
import com.petcare.module.identity.repository.AccountRepository;
import com.petcare.module.identity.repository.AccountResetState;
import com.petcare.module.identity.service.OtpService.IssuedOtp;
import com.petcare.module.identity.service.OtpService.PreparedOtp;
import com.petcare.platform.exception.BusinessRuleViolationException;

import lombok.extern.slf4j.Slf4j;

/**
 * Phần có transaction của UC04 — quên / đặt lại mật khẩu (docs/adr/0023). {@link PasswordResetService} (không
 * transaction) chạy mọi BCrypt khi không giữ connection hay khóa dòng; các method ở đây chỉ đọc, khóa và ghi trong vài
 * ms. Thứ tự khóa {@code accounts → otp_tokens → sessions} (docs/adr/0011, docs/adr/0021).
 */
@Slf4j
@Service
public class PasswordResetAttemptService {

    /** Dữ liệu đọc không khóa để so BCrypt ngoài transaction: tài khoản đủ điều kiện và có mã đặt lại còn mở. */
    public record ResetCandidate(Long accountId, String passwordHash, Long otpId, String codeHash) {

        @Override
        public String toString() {
            return "ResetCandidate[accountId=" + accountId + ", otpId=" + otpId + ", passwordHash=***, codeHash=***]";
        }
    }

    /**
     * Kết quả so BCrypt ngoài transaction, để {@link #applyReset} kiểm lại dưới khóa.
     *
     * @param otpMatched       mã nhập khớp {@code otpId}
     * @param readPasswordHash hash đọc ở bước không khóa — khác hash dưới khóa thì so lại "trùng mật khẩu hiện tại"
     * @param sameAsCurrent    mật khẩu mới trùng {@code readPasswordHash} (chỉ có nghĩa khi {@code otpMatched})
     * @param newPasswordHash  hash của {@code newPassword} khi {@code otpMatched && !sameAsCurrent}, ngược lại {@code null}
     */
    public record ResetAttempt(Long accountId, Long otpId, boolean otpMatched, String readPasswordHash,
            boolean sameAsCurrent, String newPassword, String newPasswordHash) {

        @Override
        public String toString() {
            return "ResetAttempt[accountId=" + accountId + ", otpId=" + otpId + ", otpMatched=" + otpMatched
                    + ", sameAsCurrent=" + sameAsCurrent + ", readPasswordHash=***, newPassword=***, newPasswordHash=***]";
        }
    }

    private final AccountRepository accounts;
    private final OtpService otps;
    private final SessionService sessions;
    private final NotificationApi notifications;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public PasswordResetAttemptService(AccountRepository accounts, OtpService otps, SessionService sessions,
            NotificationApi notifications, PasswordEncoder passwordEncoder, Clock clock) {
        this.accounts = accounts;
        this.otps = otps;
        this.sessions = sessions;
        this.notifications = notifications;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    /**
     * Quên mật khẩu: khóa dòng tài khoản (docs/adr/0011), rồi chỉ phát mã cho tài khoản đủ điều kiện (BR-TK-12:
     * {@code ACTIVE}, không {@code is_locked}; đang khóa tạm vẫn được). Không có BCrypt ở đây — mã đã băm ở
     * {@link OtpService#prepare}. Vi phạm BR-TK-07 ném ra ngoài, caller bắt sau khi transaction đã rollback.
     *
     * @return {@code true} nếu đã ghi mã và outbox
     */
    @Transactional
    public boolean issueResetOtp(String email, PreparedOtp prepared) {
        Optional<Account> found = accounts.findByEmailForUpdate(email).filter(PasswordResetAttemptService::eligible);
        if (found.isEmpty()) {
            return false;
        }
        Account account = found.get();
        IssuedOtp otp = otps.issuePrepared(account.getId(), OtpPurpose.RESET_PASSWORD, email, prepared);
        notifications.enqueue(new NotificationRequest(NotificationTemplateCode.OTP_PASSWORD_RESET, Channel.EMAIL, null,
                email, Map.of("ma_otp", otp.code(), "thoi_han_phut", otp.ttlMinutes()), null));
        log.info("PASSWORD_RESET_OTP_SENT accountId={}", account.getId());
        return true;
    }

    /**
     * Đặt lại mật khẩu, bước đọc không khóa: transaction readOnly ngắn, trả connection trước khi so BCrypt
     * (docs/adr/0023). Rỗng khi không có tài khoản, tài khoản không đủ điều kiện (BR-TK-12) hoặc không có mã đặt lại
     * còn mở — caller trả BR-TK-05 chung (BR-TK-10).
     */
    @Transactional(readOnly = true)
    public Optional<ResetCandidate> findResetCandidate(String email) {
        return accounts.findResetStateByEmail(email)
                .filter(AccountResetState::eligible)
                .flatMap(state -> otps.findActive(state.id(), OtpPurpose.RESET_PASSWORD, email)
                        .map(otp -> new ResetCandidate(state.id(), state.passwordHash(), otp.id(), otp.codeHash())));
    }

    /**
     * Đặt lại mật khẩu dưới khóa dòng {@code accounts} (BR-TK-05, 06, 03, 13).
     * <ol>
     *   <li>Khóa dòng; tài khoản vừa bị xóa / khóa / vô hiệu hóa sau bước đọc → BR-TK-05 chung, trước mọi lệnh ghi.</li>
     *   <li>{@link OtpService#settleCheckedOtp}: mã sai → bộ đếm được commit nhờ {@code noRollbackFor} (docs/adr/0010).</li>
     *   <li>Hash vừa đổi song song (hiếm) → so lại "trùng mật khẩu hiện tại" dưới khóa.</li>
     *   <li>Trùng mật khẩu hiện tại → 400 BR-TK-03, rollback cả {@code consumed_at}: mã vẫn dùng lại được.</li>
     *   <li>{@link Account#resetPassword}, hủy mọi phiên (BR-TK-13), email {@code PASSWORD_CHANGED}.</li>
     * </ol>
     */
    @Transactional(noRollbackFor = OtpRejectedException.class)
    public void applyReset(ResetAttempt attempt) {
        Instant now = Instant.now(clock);
        Account account = accounts.findByIdForUpdate(attempt.accountId())
                .filter(PasswordResetAttemptService::eligible)
                .orElseThrow(() -> new BusinessRuleViolationException("BR-TK-05", OtpService.INVALID_OTP_MESSAGE));

        otps.settleCheckedOtp(account.getId(), OtpPurpose.RESET_PASSWORD, account.getEmail(), attempt.otpId(),
                attempt.otpMatched());

        boolean sameAsCurrent = attempt.sameAsCurrent();
        String newPasswordHash = attempt.newPasswordHash();
        if (!account.getPasswordHash().equals(attempt.readPasswordHash())) {
            // Mật khẩu vừa đổi từ phiên khác giữa bước đọc và bước khóa: so lại với hash đọc dưới khóa (hiếm).
            sameAsCurrent = passwordEncoder.matches(attempt.newPassword(), account.getPasswordHash());
            if (!sameAsCurrent && newPasswordHash == null) {
                newPasswordHash = passwordEncoder.encode(attempt.newPassword());
            }
        }
        if (sameAsCurrent) {
            throw new BusinessRuleViolationException("BR-TK-03", NewPasswordHasher.MSG_SAME_AS_CURRENT);
        }

        account.resetPassword(newPasswordHash);
        sessions.revokeAll(account.getId());
        notifications.enqueue(new NotificationRequest(NotificationTemplateCode.PASSWORD_CHANGED, Channel.EMAIL, null,
                account.getEmail(), Map.of("thoi_diem", LoginAttemptService.UNLOCK_TIME_FORMAT.format(now)), null));
        log.info("PASSWORD_RESET accountId={}", account.getId());
    }

    /** BR-TK-12: {@code ACTIVE} và không {@code is_locked}; khóa tạm BR-TK-09 vẫn được đặt lại (BR-TK-13). */
    private static boolean eligible(Account account) {
        return account.getStatus() == AccountStatus.ACTIVE && !account.isLocked();
    }
}
