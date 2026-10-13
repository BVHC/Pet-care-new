package com.petcare.module.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.identity.service.TemporaryPasswordGenerator.TemporaryPassword;
import com.petcare.platform.security.PasswordConfig;

/**
 * Mật khẩu tạm (BR-QT-02, 10; docs/adr/0029 mục 5): với mọi {@code password.min_length} [CFG] hợp lệ (8–64, V2 dòng
 * 16) mật khẩu luôn qua {@link PasswordPolicy} (BR-TK-03), không chứa ký tự dễ nhầm, khớp hash, không lộ qua
 * {@code toString}.
 */
class TemporaryPasswordGeneratorTest {

    private static final PasswordEncoder ENCODER = new BCryptPasswordEncoder(4);

    static IntStream configuredMinLengths() {
        return IntStream.rangeClosed(8, 64);
    }

    private static SystemConfigApi configs(int minLength) {
        SystemConfigApi configs = mock(SystemConfigApi.class);
        when(configs.getInt(ConfigKey.PASSWORD_MIN_LENGTH)).thenReturn(minLength);
        return configs;
    }

    @ParameterizedTest
    @MethodSource("configuredMinLengths")
    void alwaysSatisfiesPasswordPolicy(int minLength) {
        SystemConfigApi configs = configs(minLength);
        TemporaryPassword password = new TemporaryPasswordGenerator(configs, ENCODER).prepare();
        String plain = password.plainPassword();

        assertThat(plain).hasSize(Math.max(TemporaryPasswordGenerator.MIN_LENGTH, minLength));
        assertThat(plain.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(PasswordConfig.BCRYPT_MAX_BYTES);
        assertThat(plain.chars().anyMatch(Character::isLetter)).isTrue();
        assertThat(plain.chars().anyMatch(Character::isDigit)).isTrue();
        assertThat(plain).matches("[" + TemporaryPasswordGenerator.ALPHABET + "]+");
        assertThatCode(() -> new PasswordPolicy(configs).check(plain)).doesNotThrowAnyException();
        assertThat(ENCODER.matches(plain, password.passwordHash())).isTrue();
    }

    @Test
    void neverUsesAmbiguousCharacters() {
        for (int i = 0; i < 200; i++) {
            assertThat(TemporaryPasswordGenerator.newPassword(64)).doesNotContain("0", "O", "o", "1", "l", "I");
        }
    }

    @Test
    void shortestPasswordStillHasLetterAndDigit() {
        for (int i = 0; i < 500; i++) {
            String plain = TemporaryPasswordGenerator.newPassword(2);
            assertThat(plain.chars().filter(Character::isLetter).count()).isEqualTo(1);
            assertThat(plain.chars().filter(Character::isDigit).count()).isEqualTo(1);
        }
    }

    @Test
    void passwordsDoNotRepeat() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            assertThat(seen.add(TemporaryPasswordGenerator.newPassword(12))).isTrue();
        }
    }

    @Test
    void rejectsLengthOutsideBcryptLimit() {
        assertThatThrownBy(() -> TemporaryPasswordGenerator.newPassword(1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TemporaryPasswordGenerator.newPassword(PasswordConfig.BCRYPT_MAX_BYTES + 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toStringMasksBothComponents() {
        TemporaryPassword password = new TemporaryPassword("Abc23456789x", "$2a$10$hash");

        assertThat(password.toString()).doesNotContain("Abc23456789x", "$2a$10$hash").contains("***");
    }

    /**
     * Tên thành phần phải chứa {@code password} để {@code AuditRecorder} chặn record này nếu nó lọt vào snapshot
     * audit (danh sách {@code SENSITIVE_WORDS} không có {@code mat_khau}). {@code AuditRecorderTest} kiểm hai tên này
     * là khóa nhạy cảm; test này ghim tên thành phần, đổi tên là đỏ.
     */
    @Test
    void componentNamesAreRecognisedAsSensitiveByAudit() {
        assertThat(Arrays.stream(TemporaryPassword.class.getRecordComponents()).map(RecordComponent::getName))
                .containsExactly("plainPassword", "passwordHash");
    }
}
