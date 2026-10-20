package com.petcare.module.sales.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/** Body của tạo Order bán lẻ (#1) và hủy Order khách không thanh toán (#10); lý do trống là BR-BH-05 ở service. */
class RetailOrderRequestValidationTest {

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

    private static Set<String> messages(Object request) {
        return validator.validate(request).stream().map(ConstraintViolation::getMessage).collect(Collectors.toSet());
    }

    @Test
    void customerIsRequired() {
        assertThat(messages(new CreateRetailOrderRequest(null))).containsExactly("Vui lòng chọn khách hàng");
        assertThat(messages(new CreateRetailOrderRequest(5L))).isEmpty();
    }

    @Test
    void reasonUpTo300CharactersAndBlankIsLeftToTheService() {
        assertThat(messages(new OrderReasonRequest("x".repeat(300)))).isEmpty();
        assertThat(messages(new OrderReasonRequest("x".repeat(301)))).containsExactly("Lý do tối đa 300 ký tự");
        assertThat(messages(new OrderReasonRequest(null))).isEmpty();
        assertThat(messages(new OrderReasonRequest("   "))).isEmpty();
    }
}
