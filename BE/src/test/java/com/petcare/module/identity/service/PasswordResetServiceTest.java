package com.petcare.module.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.identity.dto.ForgotPasswordRequest;
import com.petcare.module.identity.dto.OtpSentResponse;
import com.petcare.module.identity.dto.ResetPasswordRequest;
import com.petcare.module.identity.mapper.PasswordResetMapper;
import com.petcare.module.identity.service.OtpService.PreparedOtp;
import com.petcare.module.identity.service.PasswordResetAttemptService.ResetAttempt;
import com.petcare.module.identity.service.PasswordResetAttemptService.ResetCandidate;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.exception.BusinessRuleViolationException;

/**
 * Facade UC04 (docs/adr/0023): quên mật khẩu luôn trả cùng response và nuốt BR-TK-07 / lỗi khóa sau transaction;
 * đặt lại mật khẩu kiểm chính sách trước khi đọc DB, chạy đúng 2 lần {@code matches} ở mọi nhánh (BR-TK-10) và chỉ
 * {@code encode} khi mã đúng và không trùng mật khẩu hiện tại. Mapper thật (MapStruct).
 */
class PasswordResetServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-08T02:00:00Z");
    private static final String EMAIL = "khach@petcare.test";
    private static final String CURRENT = "matkhau123";
    private static final String NEW = "matkhaumoi9";
    private static final String CODE = "123456";
    private static final OtpSentResponse SAME_RESPONSE = new OtpSentResponse(NOW.plusSeconds(60), null);
    private static final PasswordEncoder FAST = new BCryptPasswordEncoder(4);

    private final PasswordResetAttemptService attempts = mock(PasswordResetAttemptService.class);
    private final OtpService otps = mock(OtpService.class);
    private final SystemConfigApi configs = mock(SystemConfigApi.class);
    private final PasswordEncoder encoder = spy(new BCryptPasswordEncoder(4));
    private final BcryptDecoy decoy = new BcryptDecoy(encoder);
    private final PasswordResetService service = new PasswordResetService(attempts, otps, new PasswordPolicy(configs),
            encoder, decoy, configs, Mappers.getMapper(PasswordResetMapper.class),
            Clock.fixed(NOW, TimeConfig.BUSINESS_ZONE));

    private final PreparedOtp prepared = new PreparedOtp("246810", "hash");
    private final ResetCandidate candidate = new ResetCandidate(7L, FAST.encode(CURRENT), 41L, FAST.encode(CODE));

    @BeforeEach
    void setUp() {
        when(configs.getInt(ConfigKey.OTP_RESEND_INTERVAL_SECONDS)).thenReturn(60);
        when(configs.getInt(ConfigKey.PASSWORD_MIN_LENGTH)).thenReturn(8);
        when(otps.prepare()).thenReturn(prepared);
        clearInvocations(encoder);   // bỏ lần encode tạo hash giả trong constructor của BcryptDecoy
    }

    // ---------------------------------------------------------------- quên mật khẩu (BR-TK-10, 12, 07)

    @Test
    void forgotNormalizesEmailPreparesOnceAndReturnsSameResponseWhenSent() {
        when(attempts.issueResetOtp(EMAIL, prepared)).thenReturn(true);

        OtpSentResponse response = service.requestPasswordReset(new ForgotPasswordRequest("  Khach@PetCare.TEST "));

        assertThat(response).isEqualTo(SAME_RESPONSE);
        verify(otps, times(1)).prepare();
        verify(attempts).issueResetOtp(EMAIL, prepared);
    }

    @Test
    void forgotReturnsSameResponseWhenNothingSent() {
        when(attempts.issueResetOtp(EMAIL, prepared)).thenReturn(false);

        assertThat(service.requestPasswordReset(new ForgotPasswordRequest(EMAIL))).isEqualTo(SAME_RESPONSE);
        verify(otps, times(1)).prepare();
    }

    @Test
    void forgotSwallowsQuotaViolationAfterTransaction() {
        doThrow(new BusinessRuleViolationException("BR-TK-07", "Vui lòng chờ 30 giây trước khi gửi lại mã OTP"))
                .when(attempts).issueResetOtp(EMAIL, prepared);

        assertThat(service.requestPasswordReset(new ForgotPasswordRequest(EMAIL))).isEqualTo(SAME_RESPONSE);
        verify(otps, times(1)).prepare();
    }

    @Test
    void forgotSwallowsLockFailureSoNo409RevealsTheAccount() {
        doThrow(new CannotAcquireLockException("deadlock detected")).when(attempts).issueResetOtp(EMAIL, prepared);

        assertThat(service.requestPasswordReset(new ForgotPasswordRequest(EMAIL))).isEqualTo(SAME_RESPONSE);
    }

    @Test
    void forgotRethrowsOtherRuleViolations() {
        doThrow(new BusinessRuleViolationException("BR-TK-99", "khác")).when(attempts).issueResetOtp(EMAIL, prepared);

        assertThatThrownBy(() -> service.requestPasswordReset(new ForgotPasswordRequest(EMAIL)))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageEndingWith("(BR-TK-99)");
    }

    // ---------------------------------------------------------------- đặt lại mật khẩu (BR-TK-03, 05, 10)

    @Test
    void policyViolationIsRejectedBeforeReadingDbOrBcrypt() {
        assertThatThrownBy(() -> service.resetPassword(new ResetPasswordRequest(EMAIL, CODE, "ngan1")))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageEndingWith("(BR-TK-03)");

        verifyNoInteractions(attempts);
        verify(encoder, never()).matches(any(), any());
        verify(encoder, never()).encode(any());
    }

    @Test
    void noCandidateIsBrTk05AfterTwoDecoyMatches() {
        when(attempts.findResetCandidate(EMAIL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resetPassword(new ResetPasswordRequest(" KHACH@petcare.test", CODE, NEW)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("OTP không đúng hoặc đã hết hạn (BR-TK-05)");

        verify(attempts).findResetCandidate(EMAIL);
        verify(encoder, times(2)).matches(any(), any());
        verify(encoder, never()).encode(any());
        verify(attempts, never()).applyReset(any());
    }

    @Test
    void wrongCodeStillRunsTwoMatchesAndPassesFalseToTransaction() {
        when(attempts.findResetCandidate(EMAIL)).thenReturn(Optional.of(candidate));

        service.resetPassword(new ResetPasswordRequest(EMAIL, "654321", NEW));

        verify(encoder, times(2)).matches(any(), any());
        verify(encoder, never()).encode(any());
        ResetAttempt attempt = applied();
        assertThat(attempt.otpMatched()).isFalse();
        assertThat(attempt.newPasswordHash()).isNull();
        assertThat(attempt.otpId()).isEqualTo(41L);
        assertThat(attempt.accountId()).isEqualTo(7L);
    }

    @Test
    void rightCodeEncodesNewPasswordOutsideTransaction() {
        when(attempts.findResetCandidate(EMAIL)).thenReturn(Optional.of(candidate));

        service.resetPassword(new ResetPasswordRequest(EMAIL, CODE, NEW));

        verify(encoder, times(2)).matches(any(), any());
        verify(encoder, times(1)).encode(NEW);
        ResetAttempt attempt = applied();
        assertThat(attempt.otpMatched()).isTrue();
        assertThat(attempt.sameAsCurrent()).isFalse();
        assertThat(attempt.readPasswordHash()).isEqualTo(candidate.passwordHash());
        assertThat(FAST.matches(NEW, attempt.newPasswordHash())).isTrue();
        assertThat(attempt.toString()).doesNotContain(NEW).doesNotContain(attempt.newPasswordHash());
    }

    @Test
    void rightCodeSameAsCurrentIsDecidedUnderLockWithoutEncoding() {
        when(attempts.findResetCandidate(EMAIL)).thenReturn(Optional.of(candidate));

        service.resetPassword(new ResetPasswordRequest(EMAIL, CODE, CURRENT));

        verify(encoder, never()).encode(anyString());
        ResetAttempt attempt = applied();
        assertThat(attempt.otpMatched()).isTrue();
        assertThat(attempt.sameAsCurrent()).isTrue();
        assertThat(attempt.newPasswordHash()).isNull();
    }

    @Test
    void transactionRejectionPropagates() {
        when(attempts.findResetCandidate(EMAIL)).thenReturn(Optional.of(candidate));
        doThrow(new BusinessRuleViolationException("BR-TK-03", NewPasswordHasher.MSG_SAME_AS_CURRENT))
                .when(attempts).applyReset(any());

        assertThatThrownBy(() -> service.resetPassword(new ResetPasswordRequest(EMAIL, CODE, CURRENT)))
                .hasMessage("Mật khẩu mới không được trùng mật khẩu hiện tại (BR-TK-03)");
        verify(attempts).applyReset(any());
    }

    private ResetAttempt applied() {
        ArgumentCaptor<ResetAttempt> captor = ArgumentCaptor.forClass(ResetAttempt.class);
        verify(attempts).applyReset(captor.capture());
        return captor.getValue();
    }
}
