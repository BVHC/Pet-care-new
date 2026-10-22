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
 * Vai trò của {@link MedicalRecordController} khớp visit-v1 mục B: #12 xem A07, A08 (CARETAKER chỉ xem), #13 ghi A07
 * (người được gán kiểm ở service dưới khóa lượt), #14 ghi cân nặng A07 — docs/adr/0065, 0066. Handler mới quên {@code @PreAuthorize} thì test đỏ.
 */
class MedicalRecordControllerSecurityTest {

    @Test
    void everyHandlerCarriesExactlyTheContractRoles() {
        assertThat(roles()).isEqualTo(Map.of(
                "getMedicalRecord", "hasAnyRole('VET','CARETAKER')",
                "updateMedicalRecord", "hasRole('VET')",
                "recordVisitWeight", "hasRole('VET')"));
    }

    private static Map<String, String> roles() {
        return Arrays.stream(MedicalRecordController.class.getDeclaredMethods())
                .filter(method -> AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class))
                .collect(Collectors.toMap(Method::getName, MedicalRecordControllerSecurityTest::preAuthorize));
    }

    private static String preAuthorize(Method handler) {
        PreAuthorize annotation = handler.getAnnotation(PreAuthorize.class);
        assertThat(annotation).as(handler.getName()).isNotNull();
        return annotation.value();
    }
}
