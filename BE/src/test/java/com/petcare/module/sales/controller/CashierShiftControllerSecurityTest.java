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
 * Quyền theo vai trò của {@link CashierShiftController} (sales-v1 #16–#21): mở / xem ca của mình / chốt chỉ lễ tân,
 * danh sách và đối soát chỉ BRANCH_MANAGER, chi tiết cho cả hai (chủ ca và chi nhánh kiểm ở service). Thêm handler mà
 * quên gắn quyền thì test này đỏ trước cả khi IT chạy.
 */
class CashierShiftControllerSecurityTest {

    @Test
    void everyHandlerHasTheExpectedRoleGate() {
        Map<String, String> expected = Map.of(
                "open", CashierShiftController.RECEPTIONIST,
                "getMine", CashierShiftController.RECEPTIONIST,
                "close", CashierShiftController.RECEPTIONIST,
                "list", CashierShiftController.BRANCH_MANAGER,
                "get", CashierShiftController.CASH_STAFF,
                "reconcile", CashierShiftController.BRANCH_MANAGER);
        List<Method> handlers = Arrays.stream(CashierShiftController.class.getDeclaredMethods())
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
        assertThat(CashierShiftController.RECEPTIONIST).isEqualTo("hasRole('RECEPTIONIST')");
        assertThat(CashierShiftController.BRANCH_MANAGER).isEqualTo("hasRole('BRANCH_MANAGER')");
        assertThat(CashierShiftController.CASH_STAFF).isEqualTo("hasAnyRole('BRANCH_MANAGER', 'RECEPTIONIST')");
    }
}
