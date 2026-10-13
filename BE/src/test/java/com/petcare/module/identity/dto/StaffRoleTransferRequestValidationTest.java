package com.petcare.module.identity.dto;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Hình thức của {@code POST /api/staff/{id}/change-role} và {@code /transfer} (convention 06; docs/adr/0031): mỗi lỗi
 * gắn đúng tên trường của contract, message tiếng Việt. {@code role} ngoài 4 chức vụ A05–A08 (kể cả
 * {@code SUPER_MANAGER}, {@code ADMIN}, {@code CUSTOMER}) là lỗi hình thức, không phải 403.
 */
class StaffRoleTransferRequestValidationTest {

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
    void everyBranchStaffRolePasses() {
        for (String role : new String[] {"BRANCH_MANAGER", "RECEPTIONIST", "VET", "CARETAKER"}) {
            assertThat(validator.validate(new ChangeRoleRequest(role))).as(role).isEmpty();
        }
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  ", "SUPER_MANAGER", "ADMIN", "CUSTOMER", "vet", " VET", "VET ", "DOCTOR", "123"})
    void invalidRoleIsFieldErrorOnRoleWithVietnameseMessage(String role) {
        Set<ConstraintViolation<ChangeRoleRequest>> violations = validator.validate(new ChangeRoleRequest(role));

        assertThat(violations).isNotEmpty();
        assertThat(violations.stream().map(v -> v.getPropertyPath().toString()).collect(Collectors.toSet()))
                .containsOnly("role");
        assertThat(violations).allSatisfy(v -> assertThat(v.getMessage()).isIn("Chức vụ không được để trống",
                "Chức vụ không hợp lệ"));
    }

    @Test
    void positiveBranchIdPasses() {
        assertThat(validator.validate(new TransferStaffRequest(1L))).isEmpty();
        assertThat(validator.validate(new TransferStaffRequest(Long.MAX_VALUE))).isEmpty();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0L, -1L, Long.MIN_VALUE})
    void missingOrNonPositiveBranchIdIsFieldErrorOnBranchId(Long branchId) {
        Set<ConstraintViolation<TransferStaffRequest>> violations =
                validator.validate(new TransferStaffRequest(branchId));

        assertThat(violations).hasSize(1);
        ConstraintViolation<TransferStaffRequest> violation = violations.iterator().next();
        assertThat(violation.getPropertyPath()).hasToString("branchId");
        assertThat(violation.getMessage()).isIn("Vui lòng chọn chi nhánh", "branchId phải là số dương");
    }
}
