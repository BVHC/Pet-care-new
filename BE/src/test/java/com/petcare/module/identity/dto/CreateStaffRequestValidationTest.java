package com.petcare.module.identity.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/**
 * Hình thức của {@code POST /api/staff} (convention 06; docs/adr/0030): mỗi lỗi gắn đúng tên trường của contract
 * ({@code GlobalExceptionHandler.describe} lấy {@code FieldError.getField()}), message tiếng Việt. {@code role} ngoài 5
 * chức vụ (kể cả {@code ADMIN}, {@code CUSTOMER}) là lỗi hình thức, không phải 403.
 */
class CreateStaffRequestValidationTest {

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
    void validRequestPasses() {
        for (String role : new String[] {"SUPER_MANAGER", "BRANCH_MANAGER", "RECEPTIONIST", "VET", "CARETAKER"}) {
            assertThat(validator.validate(new CreateStaffRequest("nv@petcare.test", "0901234567", "Nhân viên", role,
                    null))).as(role).isEmpty();
        }
    }

    static Stream<Arguments> invalid() {
        String long101 = "a".repeat(101);
        String email256 = "a".repeat(244) + "@petcare.test";
        return Stream.of(
                Arguments.of(new CreateStaffRequest(null, "0901234567", "A", "VET", 1L), "email"),
                Arguments.of(new CreateStaffRequest("  ", "0901234567", "A", "VET", 1L), "email"),
                Arguments.of(new CreateStaffRequest("not-an-email", "0901234567", "A", "VET", 1L), "email"),
                Arguments.of(new CreateStaffRequest(email256, "0901234567", "A", "VET", 1L), "email"),
                Arguments.of(new CreateStaffRequest("a@b.c", null, "A", "VET", 1L), "phone"),
                Arguments.of(new CreateStaffRequest("a@b.c", "", "A", "VET", 1L), "phone"),
                Arguments.of(new CreateStaffRequest("a@b.c", "090123456", "A", "VET", 1L), "phone"),
                Arguments.of(new CreateStaffRequest("a@b.c", "09012345ab", "A", "VET", 1L), "phone"),
                Arguments.of(new CreateStaffRequest("a@b.c", "0901234567", null, "VET", 1L), "fullName"),
                Arguments.of(new CreateStaffRequest("a@b.c", "0901234567", "   ", "VET", 1L), "fullName"),
                Arguments.of(new CreateStaffRequest("a@b.c", "0901234567", long101, "VET", 1L), "fullName"),
                Arguments.of(new CreateStaffRequest("a@b.c", "0901234567", "A", null, 1L), "role"),
                Arguments.of(new CreateStaffRequest("a@b.c", "0901234567", "A", "ADMIN", 1L), "role"),
                Arguments.of(new CreateStaffRequest("a@b.c", "0901234567", "A", "CUSTOMER", 1L), "role"),
                Arguments.of(new CreateStaffRequest("a@b.c", "0901234567", "A", "vet", 1L), "role"),
                Arguments.of(new CreateStaffRequest("a@b.c", "0901234567", "A", "FOO", 1L), "role"),
                Arguments.of(new CreateStaffRequest("a@b.c", "0901234567", "A", "VET", 0L), "branchId"),
                Arguments.of(new CreateStaffRequest("a@b.c", "0901234567", "A", "VET", -1L), "branchId"));
    }

    @ParameterizedTest
    @MethodSource("invalid")
    void invalidFieldIsReportedUnderItsContractName(CreateStaffRequest request, String field) {
        Set<ConstraintViolation<CreateStaffRequest>> violations = validator.validate(request);

        assertThat(violations.stream().map(v -> v.getPropertyPath().toString()).collect(Collectors.toSet()))
                .containsExactly(field);
        assertThat(violations).allSatisfy(v -> assertThat(v.getMessage()).doesNotContain("must"));
    }
}
