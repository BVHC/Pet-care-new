package com.petcare.module.visit.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/**
 * Hình thức body của visit-v1 #9 {@code ReassignRequest} (docs/adr/0063): người mới bắt buộc, dương; lý do bắt buộc
 * (BR-TN-08) sau khi bỏ khoảng trắng hai đầu (cả Unicode), tối đa 300 ký tự; message tiếng Việt.
 */
class ReassignRequestValidationTest {

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
    void validRequestPassesAndTheReasonIsStripped() {
        ReassignRequest request = new ReassignRequest(46L, "　 BS. Lan bị khóa \n");

        assertThat(validator.validate(request)).isEmpty();
        assertThat(request.reason()).isEqualTo("BS. Lan bị khóa");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "\t\n", "　"})
    void reasonIsRequired(String reason) {
        assertThat(errors(validator.validate(new ReassignRequest(46L, reason))))
                .containsExactlyEntriesOf(Map.of("reason", "Bắt buộc nhập lý do gán lại"));
    }

    @Test
    void reasonIsAtMost300CharactersAfterStripping() {
        assertThat(validator.validate(new ReassignRequest(46L, " " + "x".repeat(300) + " "))).isEmpty();
        assertThat(errors(validator.validate(new ReassignRequest(46L, "x".repeat(301)))))
                .containsExactlyEntriesOf(Map.of("reason", "Lý do tối đa 300 ký tự"));
    }

    @Test
    void assigneeIsRequiredAndPositive() {
        assertThat(errors(validator.validate(new ReassignRequest(null, "Lý do"))))
                .containsExactlyEntriesOf(Map.of("assigneeId", "Thiếu nhân viên được gán"));
        assertThat(errors(validator.validate(new ReassignRequest(0L, "Lý do"))))
                .containsExactlyEntriesOf(Map.of("assigneeId", "Mã nhân viên không hợp lệ"));
    }

    private static <T> Map<String, String> errors(Set<ConstraintViolation<T>> violations) {
        return violations.stream().collect(Collectors.toMap(v -> v.getPropertyPath().toString(),
                ConstraintViolation::getMessage));
    }
}
