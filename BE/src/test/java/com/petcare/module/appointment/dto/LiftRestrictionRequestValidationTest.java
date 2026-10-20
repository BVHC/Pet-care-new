package com.petcare.module.appointment.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
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
 * Body gỡ hạn chế (appointment-v1 {@code LiftRestrictionRequest}, docs/adr/0055): lý do bắt buộc, ≤ 300 sau khi bỏ khoảng
 * trắng hai đầu. Chứng minh giả định của thiết kế: {@code @NotBlank} một mình cho qua khoảng trắng Unicode (U+3000), nên
 * phải strip ở compact constructor trước khi validate.
 */
class LiftRestrictionRequestValidationTest {

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
    void reasonIsStrippedBeforeValidation() {
        assertThat(new LiftRestrictionRequest("  Khách vắng có lý do 　").reason()).isEqualTo("Khách vắng có lý do");
        assertThat(new LiftRestrictionRequest(null).reason()).isNull();
        assertThat(validator.validate(new LiftRestrictionRequest("x"))).isEmpty();
        assertThat(validator.validate(new LiftRestrictionRequest("đ".repeat(300)))).isEmpty();
        assertThat(validator.validate(new LiftRestrictionRequest(" " + "đ".repeat(300) + " "))).as("đếm sau strip")
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t\n", "　", " 　  "})
    void blankAfterStripIsMissing(String reason) {
        assertThat(errors(new LiftRestrictionRequest(reason))).containsExactlyEntriesOf(
                Map.of("reason", "Vui lòng nhập lý do"));
    }

    @Test
    void nullIsMissingAndTooLongIsRejected() {
        assertThat(errors(new LiftRestrictionRequest(null)))
                .containsExactlyEntriesOf(Map.of("reason", "Vui lòng nhập lý do"));
        assertThat(errors(new LiftRestrictionRequest("x".repeat(301))))
                .containsExactlyEntriesOf(Map.of("reason", "Lý do tối đa 300 ký tự"));
    }

    /** Lý do cho việc strip ở constructor: Hibernate {@code @NotBlank} dùng {@code trim()}, không bỏ U+3000. */
    @Test
    void notBlankAloneWouldAcceptIdeographicSpace() {
        record Raw(@jakarta.validation.constraints.NotBlank String reason) {
        }
        assertThat(validator.validate(new Raw("　"))).isEmpty();
        assertThat("　".strip()).isEmpty();
    }

    private static <T> Map<String, String> errors(T request) {
        return validator.validate(request).stream().collect(Collectors.toMap(
                violation -> violation.getPropertyPath().toString(), ConstraintViolation::getMessage));
    }
}
