package com.petcare.module.identity.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.platform.exception.BusinessRuleViolationException;

/**
 * BR-TK-03 — chính sách mật khẩu dùng chung (đăng ký, đổi mật khẩu). Message giữ đúng từng chữ của bản cũ trong
 * {@code RegistrationService} (RegistrationServiceTest cũng kiểm qua luồng đăng ký).
 */
class PasswordPolicyTest {

    private final SystemConfigApi configs = mock(SystemConfigApi.class);
    private final PasswordPolicy policy = new PasswordPolicy(configs);

    @BeforeEach
    void setUp() {
        when(configs.getInt(ConfigKey.PASSWORD_MIN_LENGTH)).thenReturn(8);
    }

    @Test
    void shorterThanConfiguredMinimum() {
        assertRejected("abc1234", "Mật khẩu phải có ít nhất 8 ký tự (BR-TK-03)");
    }

    @Test
    void minimumFollowsConfig() {
        when(configs.getInt(ConfigKey.PASSWORD_MIN_LENGTH)).thenReturn(10);

        assertRejected("abc123456", "Mật khẩu phải có ít nhất 10 ký tự (BR-TK-03)");
    }

    @Test
    void lengthCountsCharactersNotBytes() {
        assertThatCode(() -> policy.check("mậtkhẩu1")).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource({"abcdefgh", "12345678"})
    void needsLettersAndDigits(String password) {
        assertRejected(password, "Mật khẩu phải có cả chữ và số (BR-TK-03)");
    }

    @Test
    void over72BytesRejected() {
        assertRejected("a1" + "x".repeat(71), "Mật khẩu quá dài (tối đa 72 byte) (BR-TK-03)");
        assertRejected("a1" + "ă".repeat(36), "Mật khẩu quá dài (tối đa 72 byte) (BR-TK-03)");
    }

    @Test
    void exactly72BytesAccepted() {
        assertThatCode(() -> policy.check("a1" + "x".repeat(70))).doesNotThrowAnyException();
    }

    private void assertRejected(String password, String message) {
        assertThatThrownBy(() -> policy.check(password))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage(message);
    }
}
