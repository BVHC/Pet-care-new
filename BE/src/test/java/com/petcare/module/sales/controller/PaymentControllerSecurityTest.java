package com.petcare.module.sales.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;

/** Quyền theo vai trò của {@link PaymentController} (sales-v1 #12–#15): thu / xem trước / Order chờ thu chỉ lễ tân. */
class PaymentControllerSecurityTest {

    @Test
    void everyHandlerHasTheExpectedRoleGate() {
        Map<String, String> expected = Map.of("collect", PaymentController.RECEPTIONIST,
                "preview", PaymentController.RECEPTIONIST,
                "listPayable", PaymentController.RECEPTIONIST,
                "get", PaymentController.CASH_STAFF);
        List<Method> handlers = Arrays.stream(PaymentController.class.getDeclaredMethods())
                .filter(method -> AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class))
                .toList();

        assertThat(handlers).extracting(Method::getName).containsExactlyInAnyOrderElementsOf(expected.keySet());
        for (Method handler : handlers) {
            PreAuthorize preAuthorize = handler.getAnnotation(PreAuthorize.class);
            assertThat(preAuthorize).as(handler.getName()).isNotNull();
            assertThat(preAuthorize.value()).as(handler.getName()).isEqualTo(expected.get(handler.getName()));
        }
    }
}
