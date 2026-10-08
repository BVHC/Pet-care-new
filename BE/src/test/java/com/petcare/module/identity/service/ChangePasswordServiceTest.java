package com.petcare.module.identity.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.petcare.module.identity.dto.ChangePasswordRequest;
import com.petcare.module.identity.repository.AccountPasswordState;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/**
 * UC05 — phần không transaction (docs/adr/0022): khóa tạm → không BCrypt; BR-TK-14 (so mật khẩu hiện tại) trước
 * BR-TK-03 (mật khẩu mới) để mọi lần nhập sai đều được đếm; mã hóa mật khẩu mới chỉ khi mật khẩu hiện tại đúng.
 */
class ChangePasswordServiceTest {

    private static final long ACCOUNT_ID = 7L;
    private static final long SESSION_ID = 70L;
    private static final String CURRENT = "matkhau123";
    private static final String NEW = "matkhaumoi9";
    /** 09:00 giờ Việt Nam. */
    private static final Instant NOW = Instant.parse("2026-10-08T02:00:00Z");

    private final ChangePasswordAttemptService attempts = mock(ChangePasswordAttemptService.class);
    private final NewPasswordHasher hasher = mock(NewPasswordHasher.class);
    private final PasswordEncoder encoder = spy(new BCryptPasswordEncoder(4));
    private final BranchScope branchScope = mock(BranchScope.class);
    private final ChangePasswordService service = new ChangePasswordService(attempts, hasher, encoder, branchScope,
            Clock.fixed(NOW, TimeConfig.BUSINESS_ZONE));

    private String hash;

    @BeforeEach
    void setUp() {
        hash = encoder.encode(CURRENT);
        SecurityPrincipal principal = mock(SecurityPrincipal.class);
        when(principal.accountId()).thenReturn(ACCOUNT_ID);
        when(principal.sessionId()).thenReturn(SESSION_ID);
        when(branchScope.current()).thenReturn(principal);
        when(hasher.hash(CURRENT, NEW)).thenReturn("new-hash");
    }

    @Test
    void correctCurrentPasswordHashesNewOneThenApplies() {
        state(null);

        service.changePassword(new ChangePasswordRequest(CURRENT, NEW));

        verify(hasher).hash(CURRENT, NEW);
        verify(attempts).apply(ACCOUNT_ID, SESSION_ID, hash, CURRENT, NEW, true, "new-hash");
    }

    @Test
    void wrongCurrentPasswordAppliesAsMismatchWithoutHashingNewOne() {
        state(null);

        service.changePassword(new ChangePasswordRequest("sai12345", "yeu"));

        verifyNoInteractions(hasher);
        verify(attempts).apply(eq(ACCOUNT_ID), eq(SESSION_ID), eq(hash), eq("sai12345"), eq("yeu"), eq(false),
                isNull());
    }

    /** BR-TK-14 trước BR-TK-03: mật khẩu mới yếu không giúp lần sai mật khẩu hiện tại thoát bộ đếm. */
    @Test
    void wrongCurrentWithInvalidNewStillGoesToCounter() {
        state(null);

        service.changePassword(new ChangePasswordRequest("sai12345", "a1" + "x".repeat(80)));

        verify(attempts).apply(anyLong(), anyLong(), anyString(), anyString(), anyString(), eq(false), isNull());
    }

    @Test
    void currentPasswordOver72BytesIsWrongWithoutBcrypt() {
        state(null);
        String tooLong = CURRENT + "x".repeat(70);

        service.changePassword(new ChangePasswordRequest(tooLong, NEW));

        verify(encoder, never()).matches(eq(tooLong), any());
        verify(attempts).apply(anyLong(), anyLong(), anyString(), anyString(), anyString(), eq(false), isNull());
    }

    @Test
    void temporarilyLockedIs400BrTk09WithoutBcryptOrWrite() {
        state(NOW.plusSeconds(1));

        assertThatThrownBy(() -> service.changePassword(new ChangePasswordRequest(CURRENT, NEW)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("Tài khoản tạm khóa do đăng nhập sai nhiều lần. Vui lòng thử lại sau 09:01 08/10/2026"
                        + " (BR-TK-09)");

        verify(encoder, never()).matches(any(), any());
        verifyNoInteractions(hasher);
        verify(attempts, never()).apply(anyLong(), anyLong(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void lockEndingExactlyNowIsOver() {
        state(NOW);

        service.changePassword(new ChangePasswordRequest(CURRENT, NEW));

        verify(attempts).apply(ACCOUNT_ID, SESSION_ID, hash, CURRENT, NEW, true, "new-hash");
    }

    /** BR-TK-03 ném từ bước mã hóa: chưa mở transaction ghi. */
    @Test
    void invalidNewPasswordStopsBeforeApply() {
        state(null);
        when(hasher.hash(CURRENT, NEW)).thenThrow(new BusinessRuleViolationException("BR-TK-03", "yếu"));

        assertThatThrownBy(() -> service.changePassword(new ChangePasswordRequest(CURRENT, NEW)))
                .hasMessageEndingWith("(BR-TK-03)");

        verify(attempts, never()).apply(anyLong(), anyLong(), any(), any(), any(), anyBoolean(), any());
    }

    private void state(Instant lockedUntil) {
        when(attempts.findPasswordState(ACCOUNT_ID)).thenReturn(new AccountPasswordState(hash, lockedUntil));
    }
}
