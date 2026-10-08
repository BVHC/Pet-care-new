package com.petcare.module.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.petcare.module.identity.dto.LoginRequest;
import com.petcare.module.identity.dto.LoginResponse;
import com.petcare.module.identity.exception.InvalidCredentialsException;
import com.petcare.module.identity.exception.LoginRejectedException;
import com.petcare.module.identity.repository.AccountCredential;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;

/**
 * Facade UC03 (docs/adr/0019): chuẩn hóa email, đúng một lần BCrypt cho mọi nhánh (BR-TK-10), mật khẩu &gt; 72 byte là
 * sai (mục 7), audit nhánh 400 ghi sau khi transaction đã rollback (mục 6).
 */
class LoginServiceTest {

    private static final String PASSWORD = "matkhau123";

    private final LoginAttemptService attempts = mock(LoginAttemptService.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);
    private final PasswordEncoder encoder = spy(new BCryptPasswordEncoder(4));
    private String hash;
    private LoginService service;

    @BeforeEach
    void setUp() {
        hash = new BCryptPasswordEncoder(4).encode(PASSWORD);
        service = new LoginService(attempts, encoder, audit);
    }

    @Test
    void normalizesEmailAndPassesBcryptResult() {
        AccountCredential credential = new AccountCredential(7L, hash);
        when(attempts.findCredential("nv@petcare.test")).thenReturn(Optional.of(credential));
        LoginResponse expected = new LoginResponse("t", null, null, false);
        when(attempts.login(credential, "nv@petcare.test", PASSWORD, true, "ip", "ua")).thenReturn(expected);

        assertThat(service.login(new LoginRequest(" NV@PetCare.Test ", PASSWORD), "ip", "ua")).isSameAs(expected);

        InOrder order = inOrder(attempts, encoder);
        order.verify(attempts).findCredential("nv@petcare.test");
        order.verify(encoder).matches(PASSWORD, hash);
        order.verify(attempts).login(credential, "nv@petcare.test", PASSWORD, true, "ip", "ua");
    }

    @Test
    void wrongPasswordIsPassedAsNotMatched() {
        AccountCredential credential = new AccountCredential(7L, hash);
        when(attempts.findCredential(anyString())).thenReturn(Optional.of(credential));

        service.login(new LoginRequest("nv@petcare.test", "sai12345"), "ip", "ua");

        verify(attempts).login(credential, "nv@petcare.test", "sai12345", false, "ip", "ua");
    }

    @Test
    void unknownEmailStillRunsOneBcryptThenRejects() {
        when(attempts.findCredential(anyString())).thenReturn(Optional.empty());
        doThrow(new InvalidCredentialsException()).when(attempts).rejectUnknownEmail("la@petcare.test");

        assertThatThrownBy(() -> service.login(new LoginRequest("la@petcare.test", PASSWORD), "ip", "ua"))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(encoder, times(1)).matches(eq(PASSWORD), anyString());
        verify(attempts, never()).login(any(), any(), any(), anyBoolean(), any(), any());
        verify(audit, never()).recordIndependently(any());
    }

    @Test
    void passwordOver72BytesIsNeverComparedWithRealHash() {
        String tooLong = PASSWORD + "x".repeat(72);
        AccountCredential credential = new AccountCredential(7L, hash);
        when(attempts.findCredential(anyString())).thenReturn(Optional.of(credential));

        service.login(new LoginRequest("nv@petcare.test", tooLong), "ip", "ua");

        verify(encoder, never()).matches(tooLong, hash);
        verify(encoder, times(1)).matches(eq(tooLong), anyString());   // so với hash giả: cân thời gian
        verify(attempts).login(credential, "nv@petcare.test", tooLong, false, "ip", "ua");
    }

    @Test
    void rejectedLoginIsAuditedIndependentlyAfterRollbackThenRethrown() {
        AccountCredential credential = new AccountCredential(7L, hash);
        when(attempts.findCredential(anyString())).thenReturn(Optional.of(credential));
        AuditEntry entry = AuditEntry.of(IdentityAuditActions.LOGIN_FAILED).reason(IdentityAuditActions.REASON_PENDING);
        LoginRejectedException rejected = new LoginRejectedException("BR-TK-08", "chưa xác thực", entry);
        when(attempts.login(any(), any(), any(), anyBoolean(), any(), any())).thenThrow(rejected);

        assertThatThrownBy(() -> service.login(new LoginRequest("nv@petcare.test", PASSWORD), "ip", "ua"))
                .isSameAs(rejected);

        verify(audit).recordIndependently(entry);
        verify(audit, never()).record(any());
    }

    @Test
    void invalidCredentialsAreNotAuditedAgainByFacade() {
        when(attempts.findCredential(anyString())).thenReturn(Optional.of(new AccountCredential(7L, hash)));
        when(attempts.login(any(), any(), any(), anyBoolean(), any(), any()))
                .thenThrow(new InvalidCredentialsException());

        assertThatThrownBy(() -> service.login(new LoginRequest("nv@petcare.test", "sai12345"), "ip", "ua"))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(audit, never()).recordIndependently(any());
    }

    @Test
    void dummyHashIsComputedOnceWithSameEncoder() {
        verify(encoder, times(1)).encode(anyString());
    }
}
