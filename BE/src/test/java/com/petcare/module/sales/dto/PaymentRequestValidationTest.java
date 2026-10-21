package com.petcare.module.sales.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.petcare.module.sales.entity.PaymentMethod;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/** Bean Validation của request thu tiền và xem trước (sales-v1 #13, #14); mọi message tiếng Việt. */
class PaymentRequestValidationTest {

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
                .collect(Collectors.toSet());
    }

    private static <T> List<String> messages(T request) {
        return validator.validate(request).stream().map(ConstraintViolation::getMessage).sorted().toList();
    }

    @Test
    void validCollectRequestPasses() {
        assertThat(fields(new CreatePaymentRequest(List.of(1L, 2L), PaymentMethod.CASH, 0L))).isEmpty();
    }

    @Test
    void collectRequestRequiresOrdersMethodAndAmount() {
        assertThat(fields(new CreatePaymentRequest(null, null, null))).containsExactlyInAnyOrder("orderIds", "method",
                "amount");
        assertThat(fields(new CreatePaymentRequest(List.of(), PaymentMethod.CASH, 1L))).containsExactly("orderIds");
        assertThat(messages(new CreatePaymentRequest(null, PaymentMethod.CASH, 1L)))
                .containsExactly("Vui lòng chọn Order cần thu");
    }

    @Test
    void collectRequestRefusesNegativeAmountBadIdsDuplicatesAndTooMany() {
        assertThat(fields(new CreatePaymentRequest(List.of(1L), PaymentMethod.CASH, -1L))).containsExactly("amount");
        assertThat(fields(new CreatePaymentRequest(List.of(0L), PaymentMethod.CASH, 1L))).hasSize(1);
        assertThat(fields(new CreatePaymentRequest(Arrays.asList(1L, null), PaymentMethod.CASH, 1L))).hasSize(1);
        assertThat(messages(new CreatePaymentRequest(List.of(1L, 1L), PaymentMethod.CASH, 1L)))
                .containsExactly("Danh sách Order bị trùng");
        List<Long> tooMany = new ArrayList<>();
        for (long i = 1; i <= 21; i++) {
            tooMany.add(i);
        }
        assertThat(messages(new CreatePaymentRequest(tooMany, PaymentMethod.CASH, 1L)))
                .containsExactly("Một phiếu thu tối đa 20 Order");
        assertThat(fields(new CreatePaymentRequest(tooMany.subList(0, 20), PaymentMethod.CASH, 1L))).isEmpty();
    }

    @Test
    void previewRequestHasTheSameOrderListRules() {
        assertThat(fields(new PaymentPreviewRequest(List.of(1L)))).isEmpty();
        assertThat(fields(new PaymentPreviewRequest(null))).containsExactly("orderIds");
        assertThat(fields(new PaymentPreviewRequest(List.of()))).containsExactly("orderIds");
        assertThat(messages(new PaymentPreviewRequest(List.of(3L, 3L)))).containsExactly("Danh sách Order bị trùng");
    }
}
