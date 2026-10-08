package com.petcare.module.identity.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.identity.dto.ForgotPasswordRequest;
import com.petcare.module.identity.dto.OtpSentResponse;
import com.petcare.module.identity.dto.ResetPasswordRequest;
import com.petcare.module.identity.mapper.PasswordResetMapper;
import com.petcare.module.identity.service.PasswordResetAttemptService.ResetAttempt;
import com.petcare.module.identity.service.PasswordResetAttemptService.ResetCandidate;
import com.petcare.platform.exception.BusinessRuleViolationException;

import lombok.extern.slf4j.Slf4j;

/**
 * UC04 — quên mật khẩu ({@code POST /api/auth/password/forgot}) và đặt lại mật khẩu bằng OTP
 * ({@code POST /api/auth/password/reset}); BR-TK-03, 04, 05, 06, 07, 10, 12, 13 — docs/adr/0023. Cố ý <b>không</b>
 * {@code @Transactional}, như {@link LoginService} (docs/adr/0019 mục 4): mọi BCrypt chạy ở đây, khi không giữ
 * connection hay khóa dòng, vì hai endpoint này public — BCrypt dưới khóa sẽ cho phép làm cạn pool chỉ với vài request
 * song song vào một email. Phần đọc / ghi nằm ở {@link PasswordResetAttemptService} (bean khác, đi qua proxy).
 * <p>
 * Không tiết lộ email (BR-TK-10): quên mật khẩu luôn trả cùng một body; đặt lại trả cùng BR-TK-05 cho email lạ, tài
 * khoản không đủ điều kiện, không có mã, mã sai. Mọi nhánh chạy cùng số lần BCrypt ({@link BcryptDecoy}).
 */
@Slf4j
@Service
public class PasswordResetService {

    private final PasswordResetAttemptService attempts;
    private final OtpService otps;
    private final PasswordPolicy passwordPolicy;
    private final PasswordEncoder passwordEncoder;
    private final BcryptDecoy decoy;
    private final SystemConfigApi configs;
    private final PasswordResetMapper mapper;
    private final Clock clock;

    public PasswordResetService(PasswordResetAttemptService attempts, OtpService otps, PasswordPolicy passwordPolicy,
            PasswordEncoder passwordEncoder, BcryptDecoy decoy, SystemConfigApi configs, PasswordResetMapper mapper,
            Clock clock) {
        this.attempts = attempts;
        this.otps = otps;
        this.passwordPolicy = passwordPolicy;
        this.passwordEncoder = passwordEncoder;
        this.decoy = decoy;
        this.configs = configs;
        this.mapper = mapper;
        this.clock = clock;
    }

    /**
     * Quên mật khẩu. Mã luôn được sinh và băm trước (một lần BCrypt ở mọi nhánh), rồi chỉ ghi khi tài khoản đủ điều kiện.
     * BR-TK-07 và lỗi khóa DB được bắt ở đây — <b>sau</b> khi transaction đã rollback: bắt bên trong thì transaction đã
     * bị đánh dấu rollback-only và commit ném {@code UnexpectedRollbackException} (→ 500); để lọt thì 400/409 chỉ xảy ra
     * với email có tài khoản (lộ BR-TK-10). Cả hai không ghi gì.
     */
    public OtpSentResponse requestPasswordReset(ForgotPasswordRequest request) {
        String email = normalize(request.email());
        OtpService.PreparedOtp prepared = otps.prepare();
        try {
            attempts.issueResetOtp(email, prepared);
        } catch (BusinessRuleViolationException ex) {
            if (!"BR-TK-07".equals(ex.getRuleId())) {
                throw ex;
            }
            log.info("PASSWORD_RESET_OTP_THROTTLED");
        } catch (PessimisticLockingFailureException ex) {
            log.warn("PASSWORD_RESET_OTP_LOCK_FAILED {}", ex.getClass().getSimpleName());
        }
        Instant resendAvailableAt = Instant.now(clock)
                .plus(Duration.ofSeconds(configs.getInt(ConfigKey.OTP_RESEND_INTERVAL_SECONDS)));
        return mapper.toOtpSentResponse(resendAvailableAt, null);
    }

    /**
     * Đặt lại mật khẩu. Thứ tự: chính sách BR-TK-03 (không tiêu lượt OTP) → đọc không khóa → 2 lần BCrypt ngoài
     * transaction (mã OTP; trùng mật khẩu hiện tại) → không có ứng viên thì BR-TK-05 → mã hóa mật khẩu mới → ghi dưới khóa
     * ({@link PasswordResetAttemptService#applyReset}).
     */
    public void resetPassword(ResetPasswordRequest request) {
        String email = normalize(request.email());
        String newPassword = request.newPassword();
        passwordPolicy.check(newPassword);

        Optional<ResetCandidate> candidate = attempts.findResetCandidate(email);
        boolean otpMatched = candidate.isPresent()
                ? passwordEncoder.matches(request.code(), candidate.get().codeHash())
                : decoy.matches(request.code());
        boolean sameAsCurrent = otpMatched
                ? passwordEncoder.matches(newPassword, candidate.get().passwordHash())
                : decoy.matches(newPassword);
        if (candidate.isEmpty()) {
            throw new BusinessRuleViolationException("BR-TK-05", OtpService.INVALID_OTP_MESSAGE);
        }
        String newPasswordHash = otpMatched && !sameAsCurrent ? passwordEncoder.encode(newPassword) : null;

        ResetCandidate found = candidate.get();
        attempts.applyReset(new ResetAttempt(found.accountId(), found.otpId(), otpMatched, found.passwordHash(),
                sameAsCurrent, newPassword, newPasswordHash));
    }

    private static String normalize(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }
}
