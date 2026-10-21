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
 * Vai trò của từng handler trong {@link VisitController} và {@link MyQueueController} khớp visit-v1 mục B: #1 tiếp nhận
 * A06 (docs/adr/0059); #2 hàng đợi chi nhánh, #4 chi tiết A05–A08 (A13); #3 hàng đợi của tôi A07, A08 (docs/adr/0060).
 * Handler mới mà quên {@code @PreAuthorize} hoặc chưa có trong bảng này thì test đỏ.
 */
class VisitControllerSecurityTest {

    private static final String QUEUE = "hasAnyRole('BRANCH_MANAGER','RECEPTIONIST','VET','CARETAKER')";

    @Test
    void everyVisitHandlerCarriesExactlyTheContractRoles() {
        assertThat(roles(VisitController.class)).isEqualTo(Map.of(
                "checkInVisit", "hasRole('RECEPTIONIST')",
                "listQueue", QUEUE,
                "getVisit", QUEUE,
                "assignVisit", "hasRole('RECEPTIONIST')",
                "moveVisitInQueue", "hasRole('RECEPTIONIST')",
                "markVisitEmergency", "hasAnyRole('RECEPTIONIST','VET')",
                "callVisit", "hasAnyRole('VET','CARETAKER')",
                "cancelVisit", "hasRole('RECEPTIONIST')"));
        assertThat(VisitController.QUEUE_ROLES).isEqualTo(QUEUE);
    }

    @Test
    void myQueueIsOnlyForVetAndCaretaker() {
        assertThat(roles(MyQueueController.class)).isEqualTo(Map.of("listMyQueue", "hasAnyRole('VET','CARETAKER')"));
    }

    private static Map<String, String> roles(Class<?> controller) {
        return Arrays.stream(controller.getDeclaredMethods())
                .filter(method -> AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class))
                .collect(Collectors.toMap(Method::getName, VisitControllerSecurityTest::preAuthorize));
    }

    private static String preAuthorize(Method handler) {
        PreAuthorize annotation = handler.getAnnotation(PreAuthorize.class);
        assertThat(annotation).as(handler.getName()).isNotNull();
        return annotation.value();
    }
}
