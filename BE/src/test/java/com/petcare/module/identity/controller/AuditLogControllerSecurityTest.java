package com.petcare.module.identity.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * BR-QT-16 "chỉ ADMIN được xem", không có chức năng sửa / xóa (docs/adr/0038 mục 1): mọi handler của
 * {@link AuditLogController} phải mang {@code @PreAuthorize(ADMIN_ONLY)} và chỉ có GET. Thêm endpoint mới mà quên gắn quyền,
 * hoặc thêm endpoint ghi, thì test này đỏ trước cả khi IT chạy.
 */
class AuditLogControllerSecurityTest {

    @Test
    void everyHandlerIsAdminOnlyAndReadOnly() {
        List<Method> handlers = Arrays.stream(AuditLogController.class.getDeclaredMethods())
                .filter(method -> AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class))
                .toList();

        assertThat(handlers).isNotEmpty();
        for (Method handler : handlers) {
            PreAuthorize preAuthorize = handler.getAnnotation(PreAuthorize.class);
            assertThat(preAuthorize).as(handler.getName()).isNotNull();
            assertThat(preAuthorize.value()).as(handler.getName()).isEqualTo("hasRole('ADMIN')")
                    .isEqualTo(AuditLogController.ADMIN_ONLY);
            assertThat(handler.isAnnotationPresent(GetMapping.class)).as(handler.getName() + " chỉ GET").isTrue();
        }
    }
}
