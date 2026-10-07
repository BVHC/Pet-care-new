package com.petcare.module.identity.service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.identity.entity.OtpPurpose;
import com.petcare.module.identity.entity.OtpToken;
import com.petcare.module.identity.exception.OtpRejectedException;
import com.petcare.module.identity.repository.OtpTokenRepository;
import com.petcare.platform.exception.BusinessRuleViolationException;

/**
 * Sinh và xác thực mã OTP gửi email (BR-TK-04…07). Chạy trong transaction của use case gọi tới (đăng ký, gửi lại, quên mật khẩu,
 * đổi email, liên kết hồ sơ) nên đặt {@code MANDATORY}. Mã gốc chỉ trả về cho caller để ghi outbox, DB giữ BCrypt
 * hash (docs/adr/0009).
 */
@Service
public class OtpService {

    /** Mã gốc chỉ dùng để gửi; {@code resendAvailableAt} cho FE khóa nút gửi lại (BR-TK-07). */
    public record IssuedOtp(String code, Instant expiresAt, Instant resendAvailableAt, int ttlMinutes) {

        @Override
        public String toString() {
            return "IssuedOtp[code=***, expiresAt=" + expiresAt + ", resendAvailableAt=" + resendAvailableAt + "]";
        }
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    /** Câu báo lỗi của BR-TK-05 ("Xử lý khi vi phạm"); dùng chung cho mã sai, hết hạn, không còn mã. */
    static final String INVALID_OTP_MESSAGE = "OTP không đúng hoặc đã hết hạn";

    private final OtpTokenRepository otps;
    private final SystemConfigApi configs;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public OtpService(OtpTokenRepository otps, SystemConfigApi configs, PasswordEncoder passwordEncoder,
            Clock clock) {
        this.otps = otps;
        this.configs = configs;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    /**
     * BR-TK-07 (quota theo email, mọi mục đích) → BR-TK-05 (vô hiệu mã cũ cùng mục đích) → INSERT mã mới với
     * {@code expires_at} chốt theo {@code otp.ttl_minutes} [CFG] (BR-QT-13).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public IssuedOtp issueOtp(Long accountId, OtpPurpose purpose, String targetEmail) {
        Instant now = Instant.now(clock);
        Duration interval = Duration.ofSeconds(configs.getInt(ConfigKey.OTP_RESEND_INTERVAL_SECONDS));
        checkSendQuota(targetEmail, now, interval);

        otps.invalidateActive(targetEmail, purpose, now);

        int ttlMinutes = configs.getInt(ConfigKey.OTP_TTL_MINUTES);
        String code = newCode(configs.getInt(ConfigKey.OTP_CODE_LENGTH));
        Instant expiresAt = now.plus(Duration.ofMinutes(ttlMinutes));
        otps.save(new OtpToken(accountId, purpose, targetEmail, passwordEncoder.encode(code), expiresAt));
        return new IssuedOtp(code, expiresAt, now.plus(interval), ttlMinutes);
    }

    /**
     * Xác thực và dùng mã (BR-TK-05, 06). Đúng mã → {@code consumed_at}. Sai mã → tăng {@code failed_attempts},
     * chạm {@code otp.max_failed_attempts} [CFG] thì hủy mã. Mọi lần từ chối ném {@link OtpRejectedException}
     * <b>sau khi</b> đã ghi bộ đếm; {@code noRollbackFor} ở đây và ở use case gọi tới giữ lại lệnh ghi đó
     * (docs/adr/0010). Thiếu {@code noRollbackFor} ở method này thì transaction bị đánh dấu rollback-only → 500.
     * Bản thân method không khóa: caller phải khóa dòng {@code accounts} trước (docs/adr/0011), nếu không hai request
     * song song có thể mất một lần tăng bộ đếm.
     */
    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = OtpRejectedException.class)
    public void consumeOtp(Long accountId, OtpPurpose purpose, String targetEmail, String code) {
        Instant now = Instant.now(clock);
        OtpToken otp = otps
                .findTopByAccountIdAndPurposeAndTargetEmailAndConsumedAtIsNullAndInvalidatedAtIsNullOrderByIdDesc(
                        accountId, purpose, targetEmail)
                .filter(active -> !active.isExpiredAt(now))
                .orElseThrow(() -> new OtpRejectedException("BR-TK-05", INVALID_OTP_MESSAGE));

        if (!passwordEncoder.matches(code, otp.getCodeHash())) {
            int maxFailedAttempts = configs.getInt(ConfigKey.OTP_MAX_FAILED_ATTEMPTS);
            if (otp.recordFailedAttempt(now, maxFailedAttempts)) {
                throw new OtpRejectedException("BR-TK-06", "Nhập sai mã OTP quá " + maxFailedAttempts
                        + " lần, mã đã bị hủy. Vui lòng yêu cầu mã mới");
            }
            throw new OtpRejectedException("BR-TK-05", INVALID_OTP_MESSAGE);
        }
        otp.consume(now);
    }

    private void checkSendQuota(String targetEmail, Instant now, Duration interval) {
        Optional<OtpToken> last = otps.findTopByTargetEmailOrderByCreatedAtDesc(targetEmail);
        if (last.isPresent()) {
            Instant nextAllowed = last.get().getCreatedAt().plus(interval);
            if (now.isBefore(nextAllowed)) {
                long seconds = Math.max(1, Duration.between(now, nextAllowed).toSeconds());
                throw new BusinessRuleViolationException("BR-TK-07",
                        "Vui lòng chờ " + seconds + " giây trước khi gửi lại mã OTP");
            }
        }

        Instant windowStart = now.minus(Duration.ofMinutes(configs.getInt(ConfigKey.OTP_SEND_WINDOW_MINUTES)));
        int maxSends = configs.getInt(ConfigKey.OTP_MAX_SENDS_PER_WINDOW);
        if (otps.countByTargetEmailAndCreatedAtAfter(targetEmail, windowStart) >= maxSends) {
            long minutes = otps.findTopByTargetEmailAndCreatedAtAfterOrderByCreatedAtAsc(targetEmail, windowStart)
                    // mã cũ nhất ra khỏi cửa sổ sau (created_at − windowStart); làm tròn lên phút
                    .map(oldest -> Math.max(1, (Duration.between(windowStart, oldest.getCreatedAt()).toSeconds() + 59) / 60))
                    .orElse(1L);
            throw new BusinessRuleViolationException("BR-TK-07",
                    "Đã gửi quá nhiều mã OTP tới email này, vui lòng thử lại sau " + minutes + " phút");
        }
    }

    private static String newCode(int length) {
        StringBuilder code = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            code.append(RANDOM.nextInt(10));
        }
        return code.toString();
    }
}
