package com.petcare.module.appointment.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * appointment-v1 #2 chỉ cho A02 (khách) và A06 (lễ tân) (mục B; docs/adr/0043): mọi handler của
 * {@link AppointmentController} mang {@code @PreAuthorize} đúng hai role đó.
 */
class AppointmentControllerSecurityTest {

    @Test
    void everyHandlerIsLimitedToCustomerAndReceptionist() {
        List<Method> handlers = Arrays.stream(AppointmentController.class.getDeclaredMethods())
                .filter(method -> AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class))
                .toList();

        assertThat(handlers).isNotEmpty();
        for (Method handler : handlers) {
            PreAuthorize preAuthorize = handler.getAnnotation(PreAuthorize.class);
            assertThat(preAuthorize).as(handler.getName()).isNotNull();
            assertThat(preAuthorize.value()).as(handler.getName())
                    .isEqualTo("hasAnyRole('CUSTOMER','RECEPTIONIST')")
                    .isEqualTo(AppointmentController.CUSTOMER_OR_RECEPTIONIST);
        }
    }
}
