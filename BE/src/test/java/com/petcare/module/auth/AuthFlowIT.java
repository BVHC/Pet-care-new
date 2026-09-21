package com.petcare.module.auth;

import com.petcare.module.auth.dto.LoginRequest;
import com.petcare.module.auth.dto.LoginResponse;
import com.petcare.module.auth.dto.LogoutRequest;
import com.petcare.module.auth.dto.RefreshTokenRequest;
import com.petcare.module.auth.dto.RefreshTokenResponse;
import com.petcare.module.auth.dto.RegisterRequest;
import com.petcare.module.auth.dto.ResendOtpRequest;
import com.petcare.module.auth.dto.VerifyOtpRequest;
import com.petcare.module.auth.entity.Otp;
import com.petcare.module.auth.repository.AccountRepository;
import com.petcare.module.auth.repository.OtpRepository;
import com.petcare.module.auth.service.AuthService;
import com.petcare.module.auth.service.RegistrationOutcome;
import com.petcare.module.notification.gateway.EmailGateway;
import com.petcare.platform.enums.AccountStatus;
import com.petcare.platform.enums.OtpPurpose;
import com.petcare.platform.exception.AccountLockedException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.InvalidCredentialsException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.InvalidRefreshTokenException;
import com.petcare.platform.security.token.RefreshTokenRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Integration test full flow register->verify->resend trên Postgres thật
 * (Testcontainers, docs/convention/backend/09-testing.md), pattern giống
 * RefreshTokenServiceIT. EmailGateway bị mock để không gọi SMTP thật.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@TestPropertySource(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"})
class AuthFlowIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    private AuthService authService;

    @Autowired
    private OtpRepository otpRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @MockitoBean
    private EmailGateway emailGateway;

    private String uniqueEmail() {
        return "test-" + System.nanoTime() + "@example.com";
    }

    @Test
    void fullFlow_register_verify_resend() {
        when(emailGateway.providerName()).thenReturn("GMAIL_SMTP");
        when(emailGateway.send(anyString(), anyString(), anyString())).thenReturn("msg-id");

        String email = uniqueEmail();
        RegistrationOutcome registered = authService.registerAccount(
                new RegisterRequest(email, null, "password123", "Nguyen Van A"));
        assertThat(registered.account().getStatus()).isEqualTo(AccountStatus.PENDING_VERIFICATION);

        Otp otp = otpRepository.findFirstByEmailAndPurposeOrderByCreatedAtDesc(email, OtpPurpose.REGISTRATION).orElseThrow();
        String correctCode = otp.getOtpCode();

        // Sai OTP -> BUSINESS_RULE_VIOLATION, không đổi state
        org.junit.jupiter.api.Assertions.assertThrows(BusinessRuleViolationException.class,
                () -> authService.verifyOtp(new VerifyOtpRequest(email, "000000")));

        // Đúng OTP -> ACTIVE
        var account = authService.verifyOtp(new VerifyOtpRequest(email, correctCode));
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);

        // Resend sau khi đã ACTIVE -> RULE-01-03
        org.junit.jupiter.api.Assertions.assertThrows(BusinessRuleViolationException.class,
                () -> authService.resendOtp(new ResendOtpRequest(email)));
    }

    @Test
    void verifyOtp_wrongCodeFiveTimes_locksVerificationSession() {
        when(emailGateway.providerName()).thenReturn("GMAIL_SMTP");
        when(emailGateway.send(anyString(), anyString(), anyString())).thenReturn("msg-id");

        String email = uniqueEmail();
        authService.registerAccount(new RegisterRequest(email, null, "password123", "Nguyen Van A"));

        for (int i = 0; i < 5; i++) {
            org.junit.jupiter.api.Assertions.assertThrows(BusinessRuleViolationException.class,
                    () -> authService.verifyOtp(new VerifyOtpRequest(email, "000000")));
        }

        Otp otp = otpRepository.findFirstByEmailAndPurposeOrderByCreatedAtDesc(email, OtpPurpose.REGISTRATION).orElseThrow();
        assertThat(otp.isCurrentlyLocked()).isTrue();

        Otp finalOtp = otp;
        BusinessRuleViolationException ex = org.junit.jupiter.api.Assertions.assertThrows(
                BusinessRuleViolationException.class,
                () -> authService.verifyOtp(new VerifyOtpRequest(email, finalOtp.getOtpCode())));
        assertThat(ex.getRuleId()).isEqualTo("RULE-01-05");
    }

    /**
     * §5.2 Concurrency: 2 request verify-otp đồng thời cùng email/mã đúng.
     * Nhờ PESSIMISTIC_WRITE trên OtpRepository.findTopByEmailAndPurpose...,
     * request thứ 2 phải chờ request 1 commit rồi đọc lại is_used=true — chỉ
     * đúng 1 request thành công (ACTIVE), request còn lại nhận lỗi "OTP đã
     * dùng" (RULE-01-02) sạch, không lỗi 500/deadlock.
     */
    @Test
    void concurrentVerifyOtp_sameCorrectCode_onlyOneSucceeds() throws Exception {
        when(emailGateway.providerName()).thenReturn("GMAIL_SMTP");
        when(emailGateway.send(anyString(), anyString(), anyString())).thenReturn("msg-id");

        String email = uniqueEmail();
        authService.registerAccount(new RegisterRequest(email, null, "password123", "Nguyen Van A"));
        Otp otp = otpRepository.findFirstByEmailAndPurposeOrderByCreatedAtDesc(email, OtpPurpose.REGISTRATION).orElseThrow();
        String correctCode = otp.getOtpCode();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Callable<Optional<AccountStatus>>> tasks = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            tasks.add(() -> {
                try {
                    return Optional.of(authService.verifyOtp(new VerifyOtpRequest(email, correctCode)).getStatus());
                } catch (BusinessRuleViolationException | InvalidStateTransitionException ex) {
                    // Race thua cuộc: request đến sau khi Account đã ACTIVE có thể nhận
                    // BusinessRuleViolationException (OTP đã dùng) HOẶC InvalidStateTransitionException
                    // (account không còn PENDING_VERIFICATION) tuỳ thời điểm chính xác 2 thread
                    // đan xen — cả 2 đều là "đã thua cuộc", không phải lỗi thật.
                    return Optional.empty();
                }
            });
        }

        List<Future<Optional<AccountStatus>>> results = executor.invokeAll(tasks, 30, TimeUnit.SECONDS);
        executor.shutdown();

        long successCount = 0;
        for (Future<Optional<AccountStatus>> f : results) {
            if (f.get().isPresent()) {
                successCount++;
                assertThat(f.get().get()).isEqualTo(AccountStatus.ACTIVE);
            }
        }
        assertThat(successCount).isEqualTo(1);

        Otp finalOtp = otpRepository.findFirstByEmailAndPurposeOrderByCreatedAtDesc(email, OtpPurpose.REGISTRATION).orElseThrow();
        assertThat(finalOtp.isUsed()).isTrue();
    }

    private String registerAndActivate(String email) {
        when(emailGateway.providerName()).thenReturn("GMAIL_SMTP");
        when(emailGateway.send(anyString(), anyString(), anyString())).thenReturn("msg-id");

        authService.registerAccount(new RegisterRequest(email, null, "password123", "Nguyen Van A"));
        Otp otp = otpRepository.findFirstByEmailAndPurposeOrderByCreatedAtDesc(email, OtpPurpose.REGISTRATION).orElseThrow();
        authService.verifyOtp(new VerifyOtpRequest(email, otp.getOtpCode()));
        return email;
    }

    /**
     * Login -> Refresh (rotate) -> Logout -> refresh token cũ (đã revoke) bị
     * từ chối. Chỉ xác nhận qua Postgres (refresh_tokens.revoked_at/reason) —
     * không assert Access Token Blacklist (Redis) vì môi trường IT hiện tại
     * chỉ dựng Testcontainers Postgres, không có Redis (ADR-0001/0002:
     * blacklist fail-open khi Redis không sẵn sàng).
     */
    @Test
    void login_refresh_logout_fullFlow() {
        String email = registerAndActivate(uniqueEmail());

        LoginResponse login = authService.login(new LoginRequest(email, "password123"), "junit-agent", "127.0.0.1");
        assertThat(login.accessToken()).isNotBlank();
        assertThat(login.refreshToken()).isNotBlank();

        RefreshTokenResponse refreshed = authService.refresh(
                new RefreshTokenRequest(login.refreshToken()), "junit-agent", "127.0.0.1");
        assertThat(refreshed.accessToken()).isNotEqualTo(login.accessToken());
        assertThat(refreshed.refreshToken()).isNotEqualTo(login.refreshToken());

        // Refresh token cũ đã bị rotate -> tái sử dụng bị từ chối (RULE-01-06 reuse detection)
        assertThatThrownBy(() -> authService.refresh(
                new RefreshTokenRequest(login.refreshToken()), "junit-agent", "127.0.0.1"))
                .isInstanceOf(InvalidRefreshTokenException.class);

        authService.logout(refreshed.accessToken(), new LogoutRequest(refreshed.refreshToken()));

        // Refresh token mới nhất đã bị logout revoke -> refresh tiếp theo bị từ chối (RULE-01-06)
        assertThatThrownBy(() -> authService.refresh(
                new RefreshTokenRequest(refreshed.refreshToken()), "junit-agent", "127.0.0.1"))
                .isInstanceOf(InvalidRefreshTokenException.class);

        var accountId = accountRepository.findByEmail(email).orElseThrow().getId();
        assertThat(refreshTokenRepository.findAllByAccountIdAndRevokedAtIsNull(accountId)).isEmpty();
    }

    /** RULE-01-07 — 5 lần sai mật khẩu liên tiếp khóa 15 phút, kể cả lần thử tiếp theo với mật khẩu đúng. */
    @Test
    void login_wrongPassword5Times_locksAccount_RULE_01_07() {
        String email = registerAndActivate(uniqueEmail());

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> authService.login(new LoginRequest(email, "wrong-password"), "junit-agent", "127.0.0.1"))
                    .isInstanceOf(InvalidCredentialsException.class);
        }
        assertThatThrownBy(() -> authService.login(new LoginRequest(email, "wrong-password"), "junit-agent", "127.0.0.1"))
                .isInstanceOf(AccountLockedException.class);

        var account = accountRepository.findByEmail(email).orElseThrow();
        assertThat(account.getStatus()).isEqualTo(AccountStatus.LOCKED);

        // Kể cả mật khẩu đúng cũng bị chặn khi đang trong cửa sổ khóa
        assertThatThrownBy(() -> authService.login(new LoginRequest(email, "password123"), "junit-agent", "127.0.0.1"))
                .isInstanceOf(AccountLockedException.class);
    }
}
