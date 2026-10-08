package com.petcare.module.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.platform.exception.BusinessRuleViolationException;

/** Đổi mật khẩu (docs/adr/0022): BR-TK-03 chạy trước BCrypt — không đường nào {@code encode} khi chưa qua chính sách. */
class NewPasswordHasherTest {

    private static final String CURRENT = "matkhau123";

    private final SystemConfigApi configs = mock(SystemConfigApi.class);
    private final PasswordEncoder encoder = spy(new BCryptPasswordEncoder(4));
    private final NewPasswordHasher hasher = new NewPasswordHasher(new PasswordPolicy(configs), encoder);

    @BeforeEach
    void setUp() {
        when(configs.getInt(ConfigKey.PASSWORD_MIN_LENGTH)).thenReturn(8);
    }

    @Test
    void validNewPasswordIsEncoded() {
        String hash = hasher.hash(CURRENT, "matkhaumoi9");

        assertThat(encoder.matches("matkhaumoi9", hash)).isTrue();
    }

    @Test
    void sameAsCurrentRejectedWithoutBcrypt() {
        assertThatThrownBy(() -> hasher.hash(CURRENT, CURRENT))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("Mật khẩu mới không được trùng mật khẩu hiện tại (BR-TK-03)");
        verify(encoder, never()).encode(any());
    }

    @Test
    void weakPasswordRejectedBeforeSameCheckAndBcrypt() {
        assertThatThrownBy(() -> hasher.hash("abc", "abc"))
                .hasMessage("Mật khẩu phải có ít nhất 8 ký tự (BR-TK-03)");
        verify(encoder, never()).encode(any());
    }

    /** BCrypt ném lỗi (→ 500) với chuỗi &gt; 72 byte: chính sách phải chặn trước bằng 400 BR-TK-03. */
    @Test
    void over72BytesRejectedBeforeBcrypt() {
        assertThatThrownBy(() -> hasher.hash(CURRENT, "a1" + "x".repeat(71)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageEndingWith("(BR-TK-03)");
        verify(encoder, never()).encode(any());
    }
}
