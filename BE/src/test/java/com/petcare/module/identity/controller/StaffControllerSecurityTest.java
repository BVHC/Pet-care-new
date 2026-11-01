package com.petcare.module.identity.controller;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Vai trò của từng handler trong {@link StaffController} (identity-v1 #15–#24, #38; docs/adr/0029, 0030, 0089).
 * {@code SecurityConfig} chỉ đòi "đã đăng nhập", nên handler mới mà quên {@code @PreAuthorize} sẽ mở cho cả khách hàng:
 * test này đỏ trước cả khi IT chạy. Quan trọng nhất: quản trị nhân sự ({@code STAFF_ADMINS}) <b>không có</b>
 * RECEPTIONIST; chỉ danh sách gán lượt (#38) mở cho lễ tân.
 */
class StaffControllerSecurityTest {

    private static final String STAFF_ADMINS = "hasAnyRole('ADMIN', 'SUPER_MANAGER', 'BRANCH_MANAGER')";
    private static final String SUPER_MANAGER_ONLY = "hasRole('SUPER_MANAGER')";
    private static final String ASSIGNERS = "hasAnyRole('RECEPTIONIST','BRANCH_MANAGER','SUPER_MANAGER')";

    @Test
    void everyStaffHandlerCarriesExactlyItsContractRoles() {
        assertThat(roles(StaffController.class)).isEqualTo(Map.ofEntries(
                entry("list", STAFF_ADMINS),
                entry("create", STAFF_ADMINS),
                entry("get", STAFF_ADMINS),
                entry("changeRole", SUPER_MANAGER_ONLY),
                entry("transfer", SUPER_MANAGER_ONLY),
                entry("disable", STAFF_ADMINS),
                entry("reactivate", STAFF_ADMINS),
                entry("startEmailChange", STAFF_ADMINS),
                entry("confirmEmailChange", STAFF_ADMINS),
                entry("resendInitialPassword", STAFF_ADMINS),
                entry("listAssignableStaff", ASSIGNERS)));
        assertThat(StaffController.STAFF_ADMINS).isEqualTo(STAFF_ADMINS);
        assertThat(StaffController.SUPER_MANAGER_ONLY).isEqualTo(SUPER_MANAGER_ONLY);
        assertThat(StaffController.ASSIGNERS).isEqualTo(ASSIGNERS);
    }

    @Test
    void onlyTheAssignableListIsOpenToTheReceptionist() {
        assertThat(STAFF_ADMINS).doesNotContain("RECEPTIONIST");
        assertThat(roles(StaffController.class).entrySet().stream()
                .filter(handler -> handler.getValue().contains("RECEPTIONIST"))
                .map(Map.Entry::getKey))
                .containsExactly("listAssignableStaff");
    }

    @Test
    void theAssignableListIsReadOnlyAndNeverReachesAdminVetCaretakerOrCustomer() {
        Method handler = Arrays.stream(StaffController.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("listAssignableStaff")).findFirst().orElseThrow();

        assertThat(handler.isAnnotationPresent(GetMapping.class)).isTrue();
        assertThat(handler.getAnnotation(GetMapping.class).value()).containsExactly("/assignable");
        assertThat(StaffController.ASSIGNERS).doesNotContain("ADMIN", "VET", "CARETAKER", "CUSTOMER");
    }

    private static Map<String, String> roles(Class<?> controller) {
        return Arrays.stream(controller.getDeclaredMethods())
                .filter(method -> AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class))
                .collect(Collectors.toMap(Method::getName, StaffControllerSecurityTest::preAuthorize));
    }

    private static String preAuthorize(Method handler) {
        PreAuthorize annotation = handler.getAnnotation(PreAuthorize.class);
        assertThat(annotation).as(handler.getName()).isNotNull();
        return annotation.value();
    }
}
