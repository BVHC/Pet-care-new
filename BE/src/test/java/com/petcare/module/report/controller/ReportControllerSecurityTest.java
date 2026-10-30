package com.petcare.module.report.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Quyền của {@link ReportController} (UC89, BR-BC-01; docs/adr/0087 G5): chỉ SUPER_MANAGER và BRANCH_MANAGER xem báo
 * cáo; ADMIN (kỹ thuật) và nhân viên chi nhánh khác không. Phạm vi chi nhánh do service kiểm. Thêm handler mà quên gắn
 * quyền thì test này đỏ trước cả khi IT chạy.
 */
class ReportControllerSecurityTest {

    @Test
    void everyHandlerIsGatedToTheTwoManagerRoles() {
        List<Method> handlers = Arrays.stream(ReportController.class.getDeclaredMethods())
                .filter(method -> AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class))
                .toList();

        assertThat(handlers).extracting(Method::getName).containsExactlyInAnyOrder("revenue", "lostRevenue",
                "completedServices", "noShow", "revaccinationReturn", "stockAlerts", "feedback");
        for (Method handler : handlers) {
            PreAuthorize preAuthorize = handler.getAnnotation(PreAuthorize.class);
            assertThat(preAuthorize).as(handler.getName()).isNotNull();
            assertThat(preAuthorize.value()).as(handler.getName()).isEqualTo(ReportAccess.MANAGERS);
        }
    }

    @Test
    void roleSetMatchesTheContractActors() {
        assertThat(ReportAccess.MANAGERS).isEqualTo("hasAnyRole('SUPER_MANAGER', 'BRANCH_MANAGER')");
    }
}
