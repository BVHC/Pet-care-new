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
 * Body của {@code PUT /api/system-configs/{key}} (identity-v1 {@code UpdateConfigRequest}; docs/adr/0036): chỉ hình thức —
 * bắt buộc, không toàn dấu cách, tối đa 255 ({@code system_configs.value}). Kiểu / khoảng kiểm ở service (BR-QT-13).
 */
class UpdateConfigRequestValidationTest {

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
    void anyNonBlankValueUpTo255Passes() {
        assertThat(validator.validate(new UpdateConfigRequest("5"))).isEmpty();
        assertThat(validator.validate(new UpdateConfigRequest("abc"))).isEmpty();
        assertThat(validator.validate(new UpdateConfigRequest("9".repeat(255)))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   ", "\t\n"})
    void blankValueIsRejected(String value) {
        assertThat(messages(new UpdateConfigRequest(value))).containsExactly("Vui lòng nhập giá trị");
    }

    @Test
    void missingValueIsFieldErrorOnValue() {
        Set<ConstraintViolation<UpdateConfigRequest>> violations = validator.validate(new UpdateConfigRequest(null));

        assertThat(violations).extracting(ConstraintViolation::getMessage).containsExactly("Vui lòng nhập giá trị");
        violations.forEach(violation -> assertThat(violation.getPropertyPath()).hasToString("value"));
    }

    @Test
    void valueOver255IsRejected() {
        assertThat(messages(new UpdateConfigRequest("9".repeat(256)))).containsExactly("Giá trị tối đa 255 ký tự");
    }

    private static Set<String> messages(UpdateConfigRequest request) {
        return validator.validate(request).stream().map(ConstraintViolation::getMessage).collect(Collectors.toSet());
    }
}
