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
 * hash (docs/adr/0009). Đăng ký dùng {@link #issueOtp} / {@link #consumeOtp} (BCrypt trong khóa dòng, tài khoản
 * {@code PENDING}); quên / đặt lại mật khẩu dùng {@link #prepare}, {@link #issuePrepared}, {@link #findActive},
 * {@link #settleCheckedOtp} để BCrypt chạy ngoài transaction (docs/adr/0023).
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

    /** Mã và hash sinh ngoài transaction (UC04, {@link #prepare}); mã gốc chỉ để ghi outbox. */
    public record PreparedOtp(String code, String codeHash) {

        @Override
        public String toString() {
            return "PreparedOtp[code=***, codeHash=***]";
        }
    }

    /** Mã còn hiệu lực đọc ở bước không khóa (UC04, {@link #findActive}). */
    public record ActiveOtp(Long id, String codeHash, Instant expiresAt) {

        @Override
        public String toString() {
            return "ActiveOtp[id=" + id + ", codeHash=***, expiresAt=" + expiresAt + "]";
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
        checkCustomerMatchesPurpose(purpose, null);
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
        checkCustomerMatchesPurpose(purpose, null);
        Instant now = Instant.now(clock);
        OtpToken otp = otps
                .findTopByAccountIdAndPurposeAndTargetEmailAndConsumedAtIsNullAndInvalidatedAtIsNullOrderByIdDesc(
                        accountId, purpose, targetEmail)
                .filter(active -> !active.isExpiredAt(now))
                .orElseThrow(() -> new OtpRejectedException("BR-TK-05", INVALID_OTP_MESSAGE));

        if (!passwordEncoder.matches(code, otp.getCodeHash())) {
            throw recordWrongCode(otp, now);
        }
        otp.consume(now);
    }

    /**
     * UC04 (docs/adr/0023): sinh mã và băm BCrypt <b>ngoài</b> transaction, để luồng public không giữ connection hay khóa
     * dòng trong lúc BCrypt. Gọi ở mọi nhánh của quên mật khẩu, nên thời gian phản hồi như nhau dù có gửi mã hay không.
     */
    public PreparedOtp prepare() {
        String code = newCode(configs.getInt(ConfigKey.OTP_CODE_LENGTH));
        return new PreparedOtp(code, passwordEncoder.encode(code));
    }

    /**
     * Như {@link #issueOtp} với mã đã băm ở {@link #prepare}: BR-TK-07 → vô hiệu mã cũ cùng mục đích → INSERT, không
     * BCrypt. Caller đã khóa dòng {@code accounts} (docs/adr/0011). Vi phạm BR-TK-07 ném trước mọi lệnh ghi.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public IssuedOtp issuePrepared(Long accountId, OtpPurpose purpose, String targetEmail, PreparedOtp prepared) {
        return issuePrepared(accountId, purpose, targetEmail, null, prepared);
    }

    /**
     * Bản có hồ sơ cần liên kết ({@code LINK_PROFILE}, BR-TK-19 — docs/adr/0025): {@code customerId} bắt buộc với
     * {@code LINK_PROFILE}, cấm với mục đích khác (lỗi lập trình → {@link IllegalArgumentException} trước mọi lệnh ghi).
     * Với {@code LINK_PROFILE} mã cũ bị hủy theo <b>tài khoản</b> thay vì theo email: tài khoản khác xin mã cho cùng hồ sơ
     * không hủy được mã của tài khoản này, và mọi lệnh ghi lên mã của tài khoản nằm dưới khóa dòng {@code accounts} của
     * chính nó (docs/adr/0011).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public IssuedOtp issuePrepared(Long accountId, OtpPurpose purpose, String targetEmail, Long customerId,
            PreparedOtp prepared) {
        checkCustomerMatchesPurpose(purpose, customerId);
        Instant now = Instant.now(clock);
        Duration interval = Duration.ofSeconds(configs.getInt(ConfigKey.OTP_RESEND_INTERVAL_SECONDS));
        checkSendQuota(targetEmail, now, interval);

        if (customerId != null) {
            otps.invalidateActiveForAccount(accountId, purpose, now);
        } else {
            otps.invalidateActive(targetEmail, purpose, now);
        }

        int ttlMinutes = configs.getInt(ConfigKey.OTP_TTL_MINUTES);
        Instant expiresAt = now.plus(Duration.ofMinutes(ttlMinutes));
        otps.save(new OtpToken(accountId, purpose, targetEmail, customerId, prepared.codeHash(), expiresAt));
        return new IssuedOtp(prepared.code(), expiresAt, now.plus(interval), ttlMinutes);
    }

    /**
     * Bước đọc không khóa của UC04 (docs/adr/0023): mã còn hiệu lực mới nhất, để so BCrypt ngoài transaction. Chưa lọc
     * hết hạn — {@link #settleCheckedOtp} kiểm dưới khóa. Record, không trả entity.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ActiveOtp> findActive(Long accountId, OtpPurpose purpose, String targetEmail) {
        return findActive(accountId, purpose, targetEmail, null);
    }

    /** Như trên, lọc thêm theo hồ sơ cần liên kết ({@code LINK_PROFILE}, docs/adr/0025). */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ActiveOtp> findActive(Long accountId, OtpPurpose purpose, String targetEmail, Long customerId) {
        checkCustomerMatchesPurpose(purpose, customerId);
        return findLatestActive(accountId, purpose, targetEmail, customerId)
                .map(otp -> new ActiveOtp(otp.getId(), otp.getCodeHash(), otp.getExpiresAt()));
    }

    /**
     * Ghi kết quả so mã đã chạy ngoài transaction (UC04, docs/adr/0023) — không BCrypt. Caller đã khóa dòng
     * {@code accounts} (docs/adr/0011) nên đọc lại ở đây là chính xác.
     * <ul>
     *   <li>Không còn mã, mã mới nhất <b>khác</b> {@code otpId} (vừa có mã mới) hoặc đã hết hạn → BR-TK-05, không
     *       đếm: lần so vừa rồi không thuộc về mã đang hiệu lực.</li>
     *   <li>{@code matched = false} → như {@link #consumeOtp}: tăng bộ đếm, chạm ngưỡng thì hủy mã (BR-TK-05, 06),
     *       ném sau khi ghi; {@code noRollbackFor} ở đây và ở caller giữ lại lệnh ghi (docs/adr/0010).</li>
     *   <li>Đúng → {@code consumed_at}.</li>
     * </ul>
     */
    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = OtpRejectedException.class)
    public void settleCheckedOtp(Long accountId, OtpPurpose purpose, String targetEmail, Long otpId,
            boolean matched) {
        settleCheckedOtp(accountId, purpose, targetEmail, null, otpId, matched);
    }

    /**
     * Như trên, lọc thêm theo hồ sơ cần liên kết ({@code LINK_PROFILE}, docs/adr/0025): {@code otpId} của hồ sơ khác
     * → BR-TK-05 không đếm, bộ đếm của mã hồ sơ kia giữ nguyên. Use case gọi thẳng method này nên nó phải tự mang
     * {@code noRollbackFor}; gọi nội bộ từ bản trên thì proxy của bản trên áp dụng.
     */
    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = OtpRejectedException.class)
    public void settleCheckedOtp(Long accountId, OtpPurpose purpose, String targetEmail, Long customerId, Long otpId,
            boolean matched) {
        checkCustomerMatchesPurpose(purpose, customerId);
        Instant now = Instant.now(clock);
        OtpToken otp = findLatestActive(accountId, purpose, targetEmail, customerId)
                .filter(active -> active.getId().equals(otpId) && !active.isExpiredAt(now))
                .orElseThrow(() -> new OtpRejectedException("BR-TK-05", INVALID_OTP_MESSAGE));

        if (!matched) {
            throw recordWrongCode(otp, now);
        }
        otp.consume(now);
    }

    private Optional<OtpToken> findLatestActive(Long accountId, OtpPurpose purpose, String targetEmail,
            Long customerId) {
        if (customerId == null) {
            return otps.findTopByAccountIdAndPurposeAndTargetEmailAndConsumedAtIsNullAndInvalidatedAtIsNullOrderByIdDesc(
                    accountId, purpose, targetEmail);
        }
        return otps
                .findTopByAccountIdAndPurposeAndTargetEmailAndCustomerIdAndConsumedAtIsNullAndInvalidatedAtIsNullOrderByIdDesc(
                        accountId, purpose, targetEmail, customerId);
    }

    /**
     * Cùng điều kiện với CHECK {@code ck_otp_tokens_link_customer} (V1): {@code LINK_PROFILE} ⇔ có {@code customerId}.
     * Chặn ở đây để lỗi lập trình hiện ra trước mọi lệnh ghi, không phải lúc flush (docs/adr/0025).
     */
    private static void checkCustomerMatchesPurpose(OtpPurpose purpose, Long customerId) {
        if ((purpose == OtpPurpose.LINK_PROFILE) != (customerId != null)) {
            throw new IllegalArgumentException("customerId bắt buộc với LINK_PROFILE và chỉ dùng cho LINK_PROFILE: purpose="
                    + purpose + ", customerId=" + customerId);
        }
    }

    /** BR-TK-05, 06: một lần nhập sai; trả exception để caller ném sau khi bộ đếm đã nằm trong persistence context. */
    private OtpRejectedException recordWrongCode(OtpToken otp, Instant now) {
        int maxFailedAttempts = configs.getInt(ConfigKey.OTP_MAX_FAILED_ATTEMPTS);
        if (otp.recordFailedAttempt(now, maxFailedAttempts)) {
            return new OtpRejectedException("BR-TK-06", "Nhập sai mã OTP quá " + maxFailedAttempts
                    + " lần, mã đã bị hủy. Vui lòng yêu cầu mã mới");
        }
        return new OtpRejectedException("BR-TK-05", INVALID_OTP_MESSAGE);
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
