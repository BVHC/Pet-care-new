package com.petcare.module.identity.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

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
 * Hình thức của {@code POST /api/staff/{id}/reactivate} (convention 06; docs/adr/0033): {@code branchId} không bắt buộc
 * (contract {@code required=[]}); có gửi thì phải dương, lỗi gắn đúng trường {@code branchId}, message tiếng Việt.
 */
class ReactivateStaffRequestValidationTest {

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
    void missingOrPositiveBranchIdPasses() {
        assertThat(validator.validate(new ReactivateStaffRequest(null))).isEmpty();
        assertThat(validator.validate(new ReactivateStaffRequest(1L))).isEmpty();
        assertThat(validator.validate(new ReactivateStaffRequest(Long.MAX_VALUE))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L, Long.MIN_VALUE})
    void nonPositiveBranchIdIsFieldErrorOnBranchId(long branchId) {
        Set<ConstraintViolation<ReactivateStaffRequest>> violations =
                validator.validate(new ReactivateStaffRequest(branchId));

        assertThat(violations).hasSize(1);
        ConstraintViolation<ReactivateStaffRequest> violation = violations.iterator().next();
        assertThat(violation.getPropertyPath()).hasToString("branchId");
        assertThat(violation.getMessage()).isEqualTo("branchId phải là số dương");
    }
}
