package com.petcare.module.sales.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.petcare.module.sales.dto.AddOrderLineRequest.AddableLineType;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

class OrderLineRequestValidationTest {

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

    private static Set<String> invalidFields(Object request) {
        return validator.validate(request).stream().map(v -> v.getPropertyPath().toString())
                .collect(java.util.stream.Collectors.toSet());
    }

    private static String message(Object request, String field) {
        return validator.validate(request).stream().filter(v -> v.getPropertyPath().toString().equals(field))
                .map(ConstraintViolation::getMessage).findFirst().orElseThrow();
    }

    @Test
    void validAddRequestsPass() {
        assertThat(validator.validate(new AddOrderLineRequest(AddableLineType.GOODS, null, 1L, 1))).isEmpty();
        assertThat(validator.validate(new AddOrderLineRequest(AddableLineType.SERVICE, 1L, null, 1_000_000))).isEmpty();
    }

    @Test
    void addRequiresTypeAndQuantityWithinBounds() {
        assertThat(invalidFields(new AddOrderLineRequest(null, null, 1L, null))).containsExactlyInAnyOrder("lineType",
                "quantity");
        for (int quantity : new int[] {0, -1, 1_000_001}) {
            assertThat(invalidFields(new AddOrderLineRequest(AddableLineType.GOODS, null, 1L, quantity)))
                    .as("quantity " + quantity).containsExactly("quantity");
        }
    }

    @Test
    void messagesAreInVietnamese() {
        assertThat(message(new AddOrderLineRequest(null, null, 1L, 1), "lineType")).isEqualTo("Vui lòng chọn loại dòng");
        assertThat(message(new AddOrderLineRequest(AddableLineType.GOODS, null, 1L, 0), "quantity"))
                .isEqualTo("Số lượng phải lớn hơn 0");
        assertThat(message(new UpdateOrderLineRequest(0, null), "quantity")).isEqualTo("Số lượng phải lớn hơn 0");
    }

    @Test
    void updateAcceptsEitherFieldOrNoneAtAllBeanValidationWise() {
        assertThat(validator.validate(new UpdateOrderLineRequest(1, null))).isEmpty();
        assertThat(validator.validate(new UpdateOrderLineRequest(null, 5L))).isEmpty();
        assertThat(validator.validate(new UpdateOrderLineRequest(null, null))).isEmpty();
        assertThat(invalidFields(new UpdateOrderLineRequest(1_000_001, null))).containsExactly("quantity");
    }
}
