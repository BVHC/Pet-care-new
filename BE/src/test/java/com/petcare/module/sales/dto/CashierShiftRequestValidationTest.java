package com.petcare.module.sales.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/** Bean Validation của request ca thu ngân (sales-v1 #16, #18, #21); mọi message tiếng Việt. */
class CashierShiftRequestValidationTest {

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

    private static <T> Set<String> fields(T request) {
        return validator.validate(request).stream().map(v -> v.getPropertyPath().toString())
                .collect(java.util.stream.Collectors.toSet());
    }

    @Test
    void openRequestNeedsTheShiftKind() {
        assertThat(fields(new OpenShiftRequest(null))).containsExactly("afterHours");
        assertThat(fields(new OpenShiftRequest(false))).isEmpty();
        assertThat(fields(new OpenShiftRequest(true))).isEmpty();
        Set<ConstraintViolation<OpenShiftRequest>> violations = validator.validate(new OpenShiftRequest(null));
        assertThat(violations).extracting(ConstraintViolation::getMessage).containsExactly("Vui lòng chọn loại ca");
    }

    @Test
    void closeRequestNeedsANonNegativeCount() {
        assertThat(fields(new CloseShiftRequest(null))).containsExactly("countedCash");
        assertThat(fields(new CloseShiftRequest(-1L))).containsExactly("countedCash");
        assertThat(fields(new CloseShiftRequest(0L))).isEmpty();
        assertThat(validator.validate(new CloseShiftRequest(-1L))).extracting(ConstraintViolation::getMessage)
                .containsExactly("Số tiền thực đếm không được âm");
    }

    @Test
    void reconcileRequestNeedsANoteAndANonNegativeCount() {
        assertThat(fields(new ReconcileShiftRequest(null, null))).containsExactly("note");
        assertThat(fields(new ReconcileShiftRequest(null, "   "))).containsExactly("note");
        assertThat(fields(new ReconcileShiftRequest(-1L, "ok"))).containsExactly("countedCash");
        assertThat(fields(new ReconcileShiftRequest(null, "x".repeat(1001)))).containsExactly("note");
        assertThat(fields(new ReconcileShiftRequest(null, "x".repeat(1000)))).isEmpty();
        assertThat(fields(new ReconcileShiftRequest(0L, "ok"))).isEmpty();
    }
}
