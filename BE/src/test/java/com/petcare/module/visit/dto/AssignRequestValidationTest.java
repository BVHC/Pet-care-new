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
 * Hình thức body của visit-v1 #5 {@code AssignRequest} và #6 {@code MoveVisitRequest} (docs/adr/0061 D2): id bắt buộc và
 * dương, message tiếng Việt. Nhân viên / lượt đích có hợp lệ hay không là rule ở service.
 */
class AssignRequestValidationTest {

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
    void assigneeIsRequiredAndPositive() {
        assertThat(validator.validate(new AssignRequest(45L))).isEmpty();
        assertThat(errors(validator.validate(new AssignRequest(null))))
                .containsExactlyEntriesOf(Map.of("assigneeId", "Thiếu nhân viên được gán"));
        assertThat(errors(validator.validate(new AssignRequest(0L))))
                .containsExactlyEntriesOf(Map.of("assigneeId", "Mã nhân viên không hợp lệ"));
        assertThat(errors(validator.validate(new AssignRequest(-1L))))
                .containsExactlyEntriesOf(Map.of("assigneeId", "Mã nhân viên không hợp lệ"));
    }

    @Test
    void targetVisitIsRequiredAndPositive() {
        assertThat(validator.validate(new MoveVisitRequest(9002L))).isEmpty();
        assertThat(errors(validator.validate(new MoveVisitRequest(null))))
                .containsExactlyEntriesOf(Map.of("placeBeforeVisitId", "Thiếu lượt đích"));
        assertThat(errors(validator.validate(new MoveVisitRequest(0L))))
                .containsExactlyEntriesOf(Map.of("placeBeforeVisitId", "Mã lượt đích không hợp lệ"));
    }

    private static <T> Map<String, String> errors(Set<ConstraintViolation<T>> violations) {
        return violations.stream().collect(Collectors.toMap(v -> v.getPropertyPath().toString(),
                ConstraintViolation::getMessage));
    }
}
