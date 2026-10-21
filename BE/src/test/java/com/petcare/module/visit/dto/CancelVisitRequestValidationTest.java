package com.petcare.module.visit.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/**
 * Hình thức body của visit-v1 #11 {@code CancelVisitRequest} (docs/adr/0062 D2): lý do không bắt buộc (A4), tối đa 300
 * ký tự, message tiếng Việt. Bỏ khoảng trắng / rỗng thành null là việc của service.
 */
class CancelVisitRequestValidationTest {

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
    void reasonIsOptional() {
        assertThat(validator.validate(new CancelVisitRequest(null))).isEmpty();
        assertThat(validator.validate(new CancelVisitRequest(""))).isEmpty();
        assertThat(validator.validate(new CancelVisitRequest("   "))).isEmpty();
    }

    @Test
    void reasonIsAtMost300Characters() {
        assertThat(validator.validate(new CancelVisitRequest("x".repeat(300)))).isEmpty();
        assertThat(errors(validator.validate(new CancelVisitRequest("x".repeat(301)))))
                .containsExactlyEntriesOf(Map.of("reason", "Lý do tối đa 300 ký tự"));
    }

    private static <T> Map<String, String> errors(Set<ConstraintViolation<T>> violations) {
        return violations.stream().collect(Collectors.toMap(v -> v.getPropertyPath().toString(),
                ConstraintViolation::getMessage));
    }
}
