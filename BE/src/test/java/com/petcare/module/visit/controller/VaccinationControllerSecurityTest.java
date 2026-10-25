package com.petcare.module.visit.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Vai trò của {@link VaccinationController} khớp visit-v1 mục B: #18 A07 (người được gán kiểm ở service) —
 * docs/adr/0073. Handler mới quên {@code @PreAuthorize} thì test đỏ.
 */
class VaccinationControllerSecurityTest {

    @Test
    void everyHandlerCarriesExactlyTheContractRoles() {
        assertThat(roles()).isEqualTo(Map.of("getVaccinationOptions", "hasRole('VET')"));
    }

    private static Map<String, String> roles() {
        return Arrays.stream(VaccinationController.class.getDeclaredMethods())
                .filter(method -> AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class))
                .collect(Collectors.toMap(Method::getName, VaccinationControllerSecurityTest::preAuthorize));
    }

    private static String preAuthorize(Method handler) {
        PreAuthorize annotation = handler.getAnnotation(PreAuthorize.class);
        assertThat(annotation).as(handler.getName()).isNotNull();
        return annotation.value();
    }
}
