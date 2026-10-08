package com.petcare.module.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** docs/adr/0023: hash giả tạo một lần; mỗi lần {@code matches} chạy đúng một BCrypt và luôn trả {@code false}. */
class BcryptDecoyTest {

    @Test
    void hashIsCreatedOnceAndEveryMatchRunsOneBcryptAndFails() {
        PasswordEncoder encoder = spy(new BCryptPasswordEncoder(4));
        BcryptDecoy decoy = new BcryptDecoy(encoder);
        verify(encoder, times(1)).encode(any());
        clearInvocations(encoder);

        assertThat(decoy.matches("123456")).isFalse();
        assertThat(decoy.matches("")).isFalse();

        verify(encoder, times(2)).matches(any(), any());
        verify(encoder, times(0)).encode(any());
        verify(encoder).matches(eq("123456"), any());
    }
}
