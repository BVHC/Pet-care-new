package com.petcare.module.care.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.petcare.module.care.entity.CareTaskResult;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/**
 * Bean Validation của hai request (docs/adr/0080, G11). Chỉ độ dài: thiếu {@code result}, {@code note} / {@code reason}
 * null hoặc trống phải <b>đi qua</b> tầng này để service trả {@code 400 BR-TB-05} (contract) chứ không phải
 * {@code VALIDATION_FAILED} không kèm mã rule. Biên: 2000 / 2001 và 300 / 301.
 */
class CareTaskRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void start() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void stop() {
        factory.close();
    }

    @Test
    void aNoteOfExactlyTheLimitPassesAndOneMoreCharacterFailsWithAVietnameseMessage() {
        assertThat(validator.validate(new CompleteCareTaskRequest(CareTaskResult.REACHED, "a".repeat(2000)))).isEmpty();

        Set<ConstraintViolation<CompleteCareTaskRequest>> violations = validator
                .validate(new CompleteCareTaskRequest(CareTaskResult.REACHED, "a".repeat(2001)));

        assertThat(violations).hasSize(1);
        ConstraintViolation<CompleteCareTaskRequest> violation = violations.iterator().next();
        assertThat(violation.getPropertyPath().toString()).isEqualTo("note");
        assertThat(violation.getMessage()).isEqualTo("Ghi chú tối đa 2000 ký tự");
    }

    @Test
    void aReasonOfExactlyTheColumnLengthPassesAndOneMoreCharacterFails() {
        assertThat(validator.validate(new CancelCareTaskRequest("a".repeat(300)))).isEmpty();

        Set<ConstraintViolation<CancelCareTaskRequest>> violations = validator
                .validate(new CancelCareTaskRequest("a".repeat(301)));

        assertThat(violations).hasSize(1);
        ConstraintViolation<CancelCareTaskRequest> violation = violations.iterator().next();
        assertThat(violation.getPropertyPath().toString()).isEqualTo("reason");
        assertThat(violation.getMessage()).isEqualTo("Lý do tối đa 300 ký tự");
    }

    @Test
    void missingBlankAndNullValuesPassBeanValidationSoTheServiceCanAnswerBrTb05() {
        assertThat(validator.validate(new CompleteCareTaskRequest(null, null))).isEmpty();
        assertThat(validator.validate(new CompleteCareTaskRequest(null, ""))).isEmpty();
        assertThat(validator.validate(new CompleteCareTaskRequest(CareTaskResult.REACHED, "   "))).isEmpty();
        assertThat(validator.validate(new CancelCareTaskRequest(null))).isEmpty();
        assertThat(validator.validate(new CancelCareTaskRequest(""))).isEmpty();
        assertThat(validator.validate(new CancelCareTaskRequest("    "))).isEmpty();
    }
}
