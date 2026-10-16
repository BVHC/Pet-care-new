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

/**
 * Quyền theo vai trò của {@link OrderController} (sales-v1 #3–6): xem cho A05–A08, thêm / sửa / xóa dòng chỉ cho lễ tân,
 * VET, CARETAKER (BR-BH-02). Quyền chi tiết theo loại dòng và chủ dòng nằm ở service. Thêm handler mà quên gắn quyền thì
 * test này đỏ trước cả khi IT chạy.
 */
class OrderControllerSecurityTest {

    @Test
    void everyHandlerHasTheExpectedRoleGate() {
        Map<String, String> expected = Map.of(
                "get", OrderController.BRANCH_STAFF,
                "addLine", OrderController.LINE_EDITORS,
                "updateLine", OrderController.LINE_EDITORS,
                "deleteLine", OrderController.LINE_EDITORS);
        List<Method> handlers = Arrays.stream(OrderController.class.getDeclaredMethods())
                .filter(method -> AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class))
                .toList();

        assertThat(handlers).extracting(Method::getName).containsExactlyInAnyOrderElementsOf(expected.keySet());
        for (Method handler : handlers) {
            PreAuthorize preAuthorize = handler.getAnnotation(PreAuthorize.class);
            assertThat(preAuthorize).as(handler.getName()).isNotNull();
            assertThat(preAuthorize.value()).as(handler.getName()).isEqualTo(expected.get(handler.getName()));
        }
    }

    @Test
    void roleSetsMatchTheContractActors() {
        assertThat(OrderController.BRANCH_STAFF).isEqualTo("hasAnyRole('BRANCH_MANAGER', 'RECEPTIONIST', 'VET', 'CARETAKER')");
        assertThat(OrderController.LINE_EDITORS).isEqualTo("hasAnyRole('RECEPTIONIST', 'VET', 'CARETAKER')");
    }
}
