package com.petcare.module.identity.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/**
 * Lý do khóa / mở khóa (identity-v1 {@code ReasonRequest}; BR-QT-11, 03 Tài khoản#7, #8 "Có lý do" — docs/adr/0035):
 * bắt buộc, không chỉ toàn dấu cách, tối đa 500 (đơn vị UTF-16 — emoji tính 2, chặt hơn contract, không lỏng hơn). Lỗi
 * gắn đúng tên trường {@code reason}, message tiếng Việt.
 */
class ReasonRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void open() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void close() {
        factory.close();
    }

    @Test
    void reasonUpTo500CharactersPasses() {
        assertThat(validator.validate(new ReasonRequest("Spam feedback"))).isEmpty();
        assertThat(validator.validate(new ReasonRequest("x"))).isEmpty();
        assertThat(validator.validate(new ReasonRequest("đ".repeat(500)))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   ", "\t\n"})
    void blankReasonIsRejected(String reason) {
        assertThat(messages(new ReasonRequest(reason))).containsExactly("Vui lòng nhập lý do");
    }

    @Test
    void missingReasonIsFieldErrorOnReason() {
        Set<ConstraintViolation<ReasonRequest>> violations = validator.validate(new ReasonRequest(null));
        assertThat(violations).extracting(ConstraintViolation::getMessage).containsExactly("Vui lòng nhập lý do");
        violations.forEach(violation -> assertThat(violation.getPropertyPath()).hasToString("reason"));
    }

    @Test
    void reasonLongerThan500IsRejected() {
        assertThat(messages(new ReasonRequest("a".repeat(501)))).containsExactly("Lý do tối đa 500 ký tự");
        // 250 emoji = 500 đơn vị UTF-16 → hợp lệ; 251 → 502 đơn vị → từ chối (chặt hơn 500 code point của contract).
        assertThat(validator.validate(new ReasonRequest("😀".repeat(250)))).isEmpty();
        assertThat(messages(new ReasonRequest("😀".repeat(251)))).containsExactly("Lý do tối đa 500 ký tự");
    }

    private static Set<String> messages(ReasonRequest request) {
        return validator.validate(request).stream().map(ConstraintViolation::getMessage).collect(Collectors.toSet());
    }
}
