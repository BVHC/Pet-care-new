package com.petcare.module.inventory.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

class SupplierRequestValidationTest {

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

    private static <T> List<String> invalid(T value) {
        return validator.validate(value).stream().map(v -> v.getPropertyPath() + ": " + v.getMessage()).sorted()
                .collect(Collectors.toList());
    }

    @Test
    void createNeedsNameAndPhone() {
        assertThat(invalid(new SupplierRequest("Minh Tâm", "0281234567", null))).isEmpty();
        assertThat(invalid(new SupplierRequest("Minh Tâm", "02812345678", "Địa chỉ"))).isEmpty();
        assertThat(invalid(new SupplierRequest(null, null, null))).hasSize(2);
        assertThat(invalid(new SupplierRequest("  ", "0281234567", null)))
                .containsExactly("name: Vui lòng nhập tên nhà cung cấp");
    }

    @Test
    void createRejectsBadPhoneAndLongFields() {
        for (String phone : new String[] {"281234567", "028123", "0281234567890", "+842812345", "028 1234567"}) {
            assertThat(invalid(new SupplierRequest("A", phone, null))).as(phone).isNotEmpty();
        }
        assertThat(invalid(new SupplierRequest("x".repeat(201), "0281234567", null))).hasSize(1);
        assertThat(invalid(new SupplierRequest("A", "0281234567", "x".repeat(301)))).hasSize(1);
    }

    @Test
    void updateAllNullIsValidAndBlankNameIsNot() {
        assertThat(invalid(new UpdateSupplierRequest(null, null, null, null))).isEmpty();
        assertThat(invalid(new UpdateSupplierRequest(null, null, "", false))).isEmpty();
        assertThat(invalid(new UpdateSupplierRequest("  ", null, null, null)))
                .containsExactly("name: Tên nhà cung cấp không được để trống");
        assertThat(invalid(new UpdateSupplierRequest(null, "123", null, null))).hasSize(1);
    }
}
