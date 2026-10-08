package com.petcare.module.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.identity.entity.OtpPurpose;
import com.petcare.module.identity.entity.OtpToken;
import com.petcare.module.identity.exception.OtpRejectedException;
import com.petcare.module.identity.repository.OtpTokenRepository;
import com.petcare.module.identity.service.OtpService.IssuedOtp;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.exception.BusinessRuleViolationException;

/**
 * BR-TK-05 (mã, hạn, vô hiệu mã cũ, dùng một lần), BR-TK-06 (sai quá số lần), BR-TK-07 (khoảng cách, quota),
 * BR-QT-13 (chốt hạn), docs/adr/0009 (hash), docs/adr/0010 (từ chối bằng {@code OtpRejectedException}).
 */
class OtpServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-06T03:00:00Z");
    private static final String EMAIL = "khach@petcare.test";

    private final OtpTokenRepository otps = mock(OtpTokenRepository.class);
    private final SystemConfigApi configs = mock(SystemConfigApi.class);
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final OtpService service = new OtpService(otps, configs, encoder, Clock.fixed(NOW, TimeConfig.BUSINESS_ZONE));

    @BeforeEach
    void setUp() {
        when(configs.getInt(ConfigKey.OTP_CODE_LENGTH)).thenReturn(6);
        when(configs.getInt(ConfigKey.OTP_TTL_MINUTES)).thenReturn(5);
        when(configs.getInt(ConfigKey.OTP_RESEND_INTERVAL_SECONDS)).thenReturn(60);
        when(configs.getInt(ConfigKey.OTP_SEND_WINDOW_MINUTES)).thenReturn(60);
        when(configs.getInt(ConfigKey.OTP_MAX_SENDS_PER_WINDOW)).thenReturn(5);
        when(configs.getInt(ConfigKey.OTP_MAX_FAILED_ATTEMPTS)).thenReturn(5);
        when(otps.findTopByTargetEmailOrderByCreatedAtDesc(EMAIL)).thenReturn(Optional.empty());
        when(otps.countByTargetEmailAndCreatedAtAfter(eq(EMAIL), any())).thenReturn(0L);
        when(otps.save(any(OtpToken.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void issuesCodeOfConfiguredLengthStoredOnlyAsHash() {
        IssuedOtp issued = service.issueOtp(9L, OtpPurpose.REGISTER, EMAIL);

        OtpToken saved = saved();
        assertThat(issued.code()).matches("\\d{6}");
        assertThat(saved.getCodeHash()).isNotEqualTo(issued.code()).doesNotContain(issued.code());
        assertThat(encoder.matches(issued.code(), saved.getCodeHash())).isTrue();
        assertThat(saved.getAccountId()).isEqualTo(9L);
        assertThat(saved.getPurpose()).isEqualTo(OtpPurpose.REGISTER);
        assertThat(saved.getTargetEmail()).isEqualTo(EMAIL);
        assertThat(saved.getCustomerId()).isNull();
        assertThat(saved.getFailedAttempts()).isZero();
        assertThat(issued.toString()).doesNotContain(issued.code());
    }

    @Test
    void codeLengthFollowsConfig() {
        when(configs.getInt(ConfigKey.OTP_CODE_LENGTH)).thenReturn(8);

        assertThat(service.issueOtp(9L, OtpPurpose.REGISTER, EMAIL).code()).matches("\\d{8}");
    }

    @Test
    void expiresAtSnapshottedFromTtlAndResendAfterInterval() {
        IssuedOtp issued = service.issueOtp(9L, OtpPurpose.REGISTER, EMAIL);

        assertThat(saved().getExpiresAt()).isEqualTo(NOW.plusSeconds(5 * 60));
        assertThat(issued.expiresAt()).isEqualTo(NOW.plusSeconds(5 * 60));
        assertThat(issued.resendAvailableAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(issued.ttlMinutes()).isEqualTo(5);
    }

    @Test
    void invalidatesPreviousCodeOfSamePurposeBeforeInsert() {
        service.issueOtp(9L, OtpPurpose.REGISTER, EMAIL);

        InOrder order = inOrder(otps);
        order.verify(otps).invalidateActive(EMAIL, OtpPurpose.REGISTER, NOW);
        order.verify(otps).save(any(OtpToken.class));
    }

    @Test
    void lastSendWithinIntervalIsRejected() {
        when(otps.findTopByTargetEmailOrderByCreatedAtDesc(EMAIL)).thenReturn(Optional.of(sentAt(NOW.minusSeconds(59))));

        assertThatThrownBy(() -> service.issueOtp(9L, OtpPurpose.REGISTER, EMAIL))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("Vui lòng chờ 1 giây trước khi gửi lại mã OTP (BR-TK-07)");
        verify(otps, never()).invalidateActive(anyString(), any(), any());
        verify(otps, never()).save(any());
    }

    @Test
    void lastSendExactlyIntervalAgoIsAllowed() {
        when(otps.findTopByTargetEmailOrderByCreatedAtDesc(EMAIL)).thenReturn(Optional.of(sentAt(NOW.minusSeconds(60))));

        assertThat(service.issueOtp(9L, OtpPurpose.REGISTER, EMAIL).code()).matches("\\d{6}");
    }

    @Test
    void fourSendsInWindowAllowFifth() {
        when(otps.countByTargetEmailAndCreatedAtAfter(EMAIL, NOW.minusSeconds(3600))).thenReturn(4L);

        assertThat(service.issueOtp(9L, OtpPurpose.REGISTER, EMAIL).code()).matches("\\d{6}");
    }

    @Test
    void fiveSendsInWindowRejectSixthWithMinutesUntilOldestLeaves() {
        Instant windowStart = NOW.minusSeconds(3600);
        when(otps.countByTargetEmailAndCreatedAtAfter(EMAIL, windowStart)).thenReturn(5L);
        when(otps.findTopByTargetEmailAndCreatedAtAfterOrderByCreatedAtAsc(EMAIL, windowStart))
                .thenReturn(Optional.of(sentAt(windowStart.plusSeconds(10 * 60 + 1))));

        assertThatThrownBy(() -> service.issueOtp(9L, OtpPurpose.REGISTER, EMAIL))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("Đã gửi quá nhiều mã OTP tới email này, vui lòng thử lại sau 11 phút (BR-TK-07)");
        verify(otps, never()).save(any());
    }

    @Test
    void ttlChangeAppliesOnlyToCodesIssuedAfterwards() {
        service.issueOtp(9L, OtpPurpose.REGISTER, EMAIL);
        Instant first = saved().getExpiresAt();
        when(configs.getInt(ConfigKey.OTP_TTL_MINUTES)).thenReturn(15);

        assertThat(first).isEqualTo(NOW.plusSeconds(5 * 60));
        assertThat(service.issueOtp(9L, OtpPurpose.REGISTER, EMAIL).expiresAt()).isEqualTo(NOW.plusSeconds(15 * 60));
    }

    // ---------------------------------------------------------------- consumeOtp (BR-TK-05, 06)

    @Test
    void correctCodeBeforeExpiryIsConsumed() {
        OtpToken otp = activeOtp("123456", NOW.plusMillis(1));

        service.consumeOtp(9L, OtpPurpose.REGISTER, EMAIL, "123456");

        assertThat(otp.getConsumedAt()).isEqualTo(NOW);
        assertThat(otp.getFailedAttempts()).isZero();
        assertThat(otp.getInvalidatedAt()).isNull();
    }

    @Test
    void noActiveCodeIsRejectedAsInvalidOrExpired() {
        when(otps.findTopByAccountIdAndPurposeAndTargetEmailAndConsumedAtIsNullAndInvalidatedAtIsNullOrderByIdDesc(
                9L, OtpPurpose.REGISTER, EMAIL)).thenReturn(Optional.empty());

        assertOtpRejected("123456", "OTP không đúng hoặc đã hết hạn (BR-TK-05)");
    }

    @Test
    void codeIsExpiredExactlyAtExpiresAtAndNotCounted() {
        OtpToken otp = activeOtp("123456", NOW);

        assertOtpRejected("123456", "OTP không đúng hoặc đã hết hạn (BR-TK-05)");
        assertThat(otp.getConsumedAt()).isNull();
        assertThat(otp.getFailedAttempts()).as("mã hết hạn: không tính lần sai").isZero();
    }

    @Test
    void wrongCodeCountsOneFailureAndKeepsCodeAlive() {
        OtpToken otp = activeOtp("123456", NOW.plusSeconds(60));

        assertOtpRejected("654321", "OTP không đúng hoặc đã hết hạn (BR-TK-05)");
        assertThat(otp.getFailedAttempts()).isEqualTo(1);
        assertThat(otp.getInvalidatedAt()).isNull();
        assertThat(otp.getConsumedAt()).isNull();
    }

    @Test
    void fourthWrongCodeStillLeavesCodeAliveWhenMaxIsFive() {
        OtpToken otp = activeOtp("123456", NOW.plusSeconds(60));
        ReflectionTestUtils.setField(otp, "failedAttempts", 3);

        assertOtpRejected("654321", "OTP không đúng hoặc đã hết hạn (BR-TK-05)");
        assertThat(otp.getFailedAttempts()).isEqualTo(4);
        assertThat(otp.getInvalidatedAt()).isNull();
    }

    @Test
    void fifthWrongCodeCancelsTheCode() {
        OtpToken otp = activeOtp("123456", NOW.plusSeconds(60));
        ReflectionTestUtils.setField(otp, "failedAttempts", 4);

        assertOtpRejected("654321", "Nhập sai mã OTP quá 5 lần, mã đã bị hủy. Vui lòng yêu cầu mã mới (BR-TK-06)");
        assertThat(otp.getFailedAttempts()).isEqualTo(5);
        assertThat(otp.getInvalidatedAt()).isEqualTo(NOW);
    }

    @Test
    void maxFailedAttemptsComesFromConfig() {
        when(configs.getInt(ConfigKey.OTP_MAX_FAILED_ATTEMPTS)).thenReturn(3);
        OtpToken otp = activeOtp("123456", NOW.plusSeconds(60));
        ReflectionTestUtils.setField(otp, "failedAttempts", 2);

        assertOtpRejected("654321", "Nhập sai mã OTP quá 3 lần, mã đã bị hủy. Vui lòng yêu cầu mã mới (BR-TK-06)");
        assertThat(otp.getInvalidatedAt()).isEqualTo(NOW);
    }

    private OtpToken activeOtp(String code, Instant expiresAt) {
        OtpToken otp = new OtpToken(9L, OtpPurpose.REGISTER, EMAIL, encoder.encode(code), expiresAt);
        when(otps.findTopByAccountIdAndPurposeAndTargetEmailAndConsumedAtIsNullAndInvalidatedAtIsNullOrderByIdDesc(
                9L, OtpPurpose.REGISTER, EMAIL)).thenReturn(Optional.of(otp));
        return otp;
    }

    private void assertOtpRejected(String code, String message) {
        assertThatThrownBy(() -> service.consumeOtp(9L, OtpPurpose.REGISTER, EMAIL, code))
                .as("docs/adr/0010: mọi lần từ chối là OtpRejectedException để bộ đếm được commit")
                .isExactlyInstanceOf(OtpRejectedException.class)
                .hasMessage(message);
    }

    private OtpToken saved() {
        ArgumentCaptor<OtpToken> captor = ArgumentCaptor.forClass(OtpToken.class);
        verify(otps, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }

    private static OtpToken sentAt(Instant createdAt) {
        OtpToken token = new OtpToken(1L, OtpPurpose.LINK_PROFILE, EMAIL, "hash", createdAt.plusSeconds(300));
        ReflectionTestUtils.setField(token, "createdAt", createdAt);
        return token;
    }
}
