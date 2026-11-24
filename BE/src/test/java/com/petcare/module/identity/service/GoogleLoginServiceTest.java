package com.petcare.module.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.petcare.module.identity.dto.GoogleLoginRequest;
import com.petcare.module.identity.dto.LoginResponse;
import com.petcare.module.identity.exception.GoogleSignInUnavailableException;
import com.petcare.module.identity.exception.InvalidCredentialsException;
import com.petcare.module.identity.exception.LoginRejectedException;
import com.petcare.module.identity.google.GoogleIdentity;
import com.petcare.module.identity.google.GoogleIdentityVerifier;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;

/**
 * Facade đăng nhập Google (docs/adr/0152): kiểm token (gọi mạng) trước và ngoài phần có transaction, hash không dùng
 * được tính một lần, audit nhánh 400 ghi sau khi transaction đã rollback (như {@link LoginService}).
 */
class GoogleLoginServiceTest {

    private static final GoogleIdentity IDENTITY = new GoogleIdentity("g-1", "lan@gmail.com", "Lan", true);
    private static final GoogleLoginRequest REQUEST = new GoogleLoginRequest("cred", true, true, null);

    private final GoogleIdentityVerifier verifier = mock(GoogleIdentityVerifier.class);
    private final LoginAttemptService attempts = mock(LoginAttemptService.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);
    private final PasswordEncoder encoder = spy(new BCryptPasswordEncoder(4));
    private GoogleLoginService service;

    @BeforeEach
    void setUp() {
        service = new GoogleLoginService(verifier, attempts, encoder, audit);
    }

    @Test
    void verifiesBeforeTransactionalPart() {
        LoginResponse expected = new LoginResponse("t", null, null, null, null, false);
        when(verifier.verify("cred")).thenReturn(IDENTITY);
        when(attempts.loginWithGoogle(eq(IDENTITY), eq(REQUEST), anyString(), eq("ip"), eq("ua"))).thenReturn(expected);

        assertThat(service.login(REQUEST, "ip", "ua")).isSameAs(expected);

        InOrder order = inOrder(verifier, attempts);
        order.verify(verifier).verify("cred");
        order.verify(attempts).loginWithGoogle(eq(IDENTITY), eq(REQUEST), anyString(), eq("ip"), eq("ua"));
    }

    @Test
    void verifierFailureNeverTouchesAttempts() {
        when(verifier.verify("cred"))
                .thenThrow(new InvalidCredentialsException(InvalidCredentialsException.GOOGLE_MESSAGE))
                .thenThrow(new GoogleSignInUnavailableException());

        assertThatThrownBy(() -> service.login(REQUEST, "ip", "ua")).isInstanceOf(InvalidCredentialsException.class);
        assertThatThrownBy(() -> service.login(REQUEST, "ip", "ua"))
                .isInstanceOf(GoogleSignInUnavailableException.class);

        verifyNoInteractions(attempts, audit);
    }

    @Test
    void rejectedLoginIsAuditedIndependentlyAndRethrown() {
        when(verifier.verify("cred")).thenReturn(IDENTITY);
        AuditEntry entry = AuditEntry.of(IdentityAuditActions.LOGIN_FAILED)
                .reason(IdentityAuditActions.REASON_GOOGLE_STAFF);
        LoginRejectedException rejected = new LoginRejectedException("BR-TK-17", "nhân viên", entry);
        when(attempts.loginWithGoogle(any(), any(), any(), any(), any())).thenThrow(rejected);

        assertThatThrownBy(() -> service.login(REQUEST, "ip", "ua")).isSameAs(rejected);

        verify(audit).recordIndependently(entry);
        verify(audit, never()).record(any());
    }

    @Test
    void unusableHashComputedOnceAndIsBcrypt() {
        when(verifier.verify("cred")).thenReturn(IDENTITY);

        service.login(REQUEST, "ip", "ua");
        service.login(REQUEST, "ip", "ua");

        verify(encoder, times(1)).encode(anyString());
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(attempts, times(2)).loginWithGoogle(eq(IDENTITY), eq(REQUEST), hash.capture(), eq("ip"), eq("ua"));
        assertThat(hash.getAllValues()).allSatisfy(value -> assertThat(value).startsWith("$2"));
        assertThat(hash.getAllValues().get(1)).isEqualTo(hash.getAllValues().get(0));
    }

    @Test
    void requestToStringMasksCredential() {
        assertThat(new GoogleLoginRequest("eyJ.secret.token", true, true, "0901234567").toString())
                .doesNotContain("eyJ.secret.token").contains("credential=***");
    }
}
