package com.petcare.module.workforce.controller;

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
 * Vai trò của từng handler của module workforce khớp workforce-v1 mục B / §D.1: ca, phân ca hộ, hủy, ghi vắng, danh
 * sách yêu cầu của chi nhánh, duyệt và từ chối chỉ BRANCH_MANAGER; lịch / yêu cầu của tôi chỉ A06–A08; riêng
 * {@code GET /shift-definitions} mở thêm cho A06–A08 (A10). SUPER_MANAGER không có chức năng nhân sự. Bảng dưới là bản
 * sao độc lập của hợp đồng (không đọc lại hằng của controller). Handler mới mà quên {@code @PreAuthorize} hoặc chưa có
 * trong bảng này thì test đỏ (khuôn {@code VisitControllerSecurityTest}).
 */
class WorkforceControllerSecurityTest {

    private static final String BRANCH_MANAGER = "hasRole('BRANCH_MANAGER')";
    private static final String STAFF = "hasAnyRole('RECEPTIONIST', 'VET', 'CARETAKER')";
    private static final String BRANCH_STAFF = "hasAnyRole('BRANCH_MANAGER', 'RECEPTIONIST', 'VET', 'CARETAKER')";

    @Test
    void everyShiftDefinitionHandlerCarriesExactlyTheContractRoles() {
        assertThat(roles(ShiftDefinitionController.class)).isEqualTo(Map.of(
                "list", BRANCH_STAFF,
                "create", BRANCH_MANAGER,
                "update", BRANCH_MANAGER));
    }

    @Test
    void everyShiftAssignmentHandlerCarriesExactlyTheContractRoles() {
        assertThat(roles(ShiftAssignmentController.class)).isEqualTo(Map.of(
                "list", BRANCH_MANAGER,
                "create", BRANCH_MANAGER,
                "cancel", BRANCH_MANAGER,
                "recordAbsence", BRANCH_MANAGER,
                "listMine", STAFF));
    }

    @Test
    void everyShiftRequestHandlerCarriesExactlyTheContractRoles() {
        assertThat(roles(ShiftRequestController.class)).isEqualTo(Map.of(
                "create", STAFF,
                "listMine", STAFF,
                "withdraw", STAFF,
                "listBranch", BRANCH_MANAGER,
                "approve", BRANCH_MANAGER,
                "reject", BRANCH_MANAGER));
    }

    @Test
    void everyShiftRegistrationHandlerCarriesExactlyTheContractRoles() {
        assertThat(roles(ShiftRegistrationController.class)).isEqualTo(Map.of(
                "getMine", STAFF,
                "putMine", STAFF));
    }

    @Test
    void theWeeklySummaryHandlerIsForTheBranchManagerOnly() {
        assertThat(roles(ShiftWeeklySummaryController.class)).isEqualTo(Map.of("list", BRANCH_MANAGER));
    }

    @Test
    void theSharedRoleConstantsHaveNotDrifted() {
        assertThat(ShiftAssignmentController.BRANCH_MANAGER).isEqualTo(BRANCH_MANAGER);
        assertThat(ShiftAssignmentController.STAFF).isEqualTo(STAFF);
        assertThat(ShiftDefinitionController.BRANCH_MANAGER).isEqualTo(BRANCH_MANAGER);
        assertThat(ShiftDefinitionController.BRANCH_STAFF).isEqualTo(BRANCH_STAFF);
    }

    private static Map<String, String> roles(Class<?> controller) {
        return Arrays.stream(controller.getDeclaredMethods())
                .filter(method -> AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class))
                .collect(Collectors.toMap(Method::getName, WorkforceControllerSecurityTest::preAuthorize));
    }

    private static String preAuthorize(Method handler) {
        PreAuthorize annotation = handler.getAnnotation(PreAuthorize.class);
        assertThat(annotation).as(handler.getName()).isNotNull();
        return annotation.value();
    }
}
